/*******************************************************************************
 * Copyright 2014-2026 Marc Lamberton
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package org.myphotodiary.cms.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.gallery.dto.ImportResponse;
import org.myphotodiary.cms.settings.AppSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Option C's background queue (27/10/2026), run for real: real ffmpeg
 * fixtures and encodes, committed data (not @Transactional - the jobs run in
 * their own transactions, like in production, and must see what the import
 * committed). Background workers are off in tests
 * (app.video.transcode-workers=0): each test runs the jobs itself with
 * {@link VideoJobRunner#runNextJob()}, so the order is checked exactly.
 * Uses the bootstrap "admin" account, committed at start-up. Cleans up its
 * own rows. Skipped without ffmpeg (or without libx265 for compaction).
 */
@SpringBootTest
class VideoTranscodingTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void storageRoot(DynamicPropertyRegistry registry) {
		registry.add("storage.root", () -> storageRoot.toString());
	}

	@Autowired
	private ImportService importService;
	@Autowired
	private DirectoryIndexerService indexerService;
	@Autowired
	private VideoJobRunner runner;
	@Autowired
	private VideoJobRepository jobRepository;
	@Autowired
	private ImageRepository imageRepository;
	@Autowired
	private DirectoryRepository directoryRepository;
	@Autowired
	private ImageStorageService storageService;
	@Autowired
	private VideoFormatValidator validator;
	@Autowired
	private AppSettingsService appSettingsService;
	@Autowired
	private TransactionTemplate transactionTemplate;

	private static boolean ffmpegAvailable;
	private static boolean x265Available;

	private final Authentication admin = new UsernamePasswordAuthenticationToken("admin", null,
			List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

	@BeforeAll
	static void checkFfmpeg() {
		ffmpegAvailable = run("ffmpeg", "-version") != null && run("ffprobe", "-version") != null;
		String encoders = ffmpegAvailable ? run("ffmpeg", "-hide_banner", "-encoders") : null;
		x265Available = encoders != null && encoders.contains("libx265");
	}

	@BeforeEach
	void requireFfmpeg() {
		assumeTrue(ffmpegAvailable, "ffmpeg/ffprobe not on PATH - skipping");
	}

	@AfterEach
	void cleanUp() {
		appSettingsService.updateCompactOriginalVideos(false);
		transactionTemplate.executeWithoutResult(status -> {
			jobRepository.deleteAll();
			directoryRepository.findAll().stream().filter(d -> d.getPath().startsWith("vt-")).forEach(directory -> {
				imageRepository.deleteAll(imageRepository.findByDirectory_IdOrderByNameAsc(directory.getId()));
				directoryRepository.delete(directory);
			});
		});
	}

	@Test
	void otherCodec_getsAnH264Copy_thatThenPlays() throws IOException {
		ImportResponse imported = importService.importImage(admin, fixture("old.mp4", "mpeg4", 320, 240, 25, true), false, false, null, "vt-copy",
				null);
		assertThat(imported.image().videoPlayback()).isEqualTo("PENDING");

		assertThat(runner.runNextJob()).isTrue();
		assertThat(runner.runNextJob()).isFalse(); // nothing else queued (compaction is off)

		Image video = imageRepository.findById(imported.image().id()).orElseThrow();
		assertThat(video.getVideoPlayback()).isEqualTo(VideoPlayback.COPY);
		Path copy = storageRoot.resolve("vt-copy/.webimg/old.mp4.mp4");
		VideoInfo copyInfo = validator.validate(copy);
		assertThat(copyInfo.videoCodec()).isEqualTo("h264");
		assertThat(copyInfo.audioCodec()).isEqualTo("aac");
		assertThat(copyInfo.playableAsIs()).isTrue();
		Path playable = transactionTemplate.execute(s -> storageService.resolvePlayableVideo(imageRepository.findById(video.getId()).orElseThrow()));
		assertThat(playable.toRealPath()).isEqualTo(copy.toRealPath()); // /var -> /private/var on macOS
		assertThat(Files.exists(storageRoot.resolve("vt-copy/old.mp4"))).isTrue(); // original kept as uploaded
		assertThat(jobRepository.findAll()).isEmpty();
	}

	@Test
	void largeH264_isCappedTo1080p_and30fps_inItsCopy_portraitKept() throws IOException {
		ImportResponse imported = importService.importImage(admin, fixture("tall.mp4", "libx264", 1440, 2560, 60, true), false, false, null,
				"vt-cap", null);
		assertThat(imported.image().videoPlayback()).isEqualTo("PENDING"); // H.264, but larger than 1080p and 60 fps

		runner.runNextJob();

		VideoInfo copy = validator.validate(storageRoot.resolve("vt-cap/.webimg/tall.mp4.mp4"));
		assertThat(copy.width()).isEqualTo(1080);
		assertThat(copy.height()).isEqualTo(1920);
		assertThat(copy.frameRate()).isLessThanOrEqualTo(30.0);
	}

	@Test
	void reindex_requeuesAMissingCopy() throws IOException {
		ImportResponse imported = importService.importImage(admin, fixture("lost.mp4", "mpeg4", 160, 120, 10, false), false, false, null,
				"vt-reindex", null);
		runner.runNextJob();
		Files.delete(storageRoot.resolve("vt-reindex/.webimg/lost.mp4.mp4"));

		indexerService.indexDirectory("vt-reindex", null);

		assertThat(imageRepository.findById(imported.image().id()).orElseThrow().getVideoPlayback()).isEqualTo(VideoPlayback.PENDING);
		assertThat(runner.runNextJob()).isTrue();
		assertThat(Files.exists(storageRoot.resolve("vt-reindex/.webimg/lost.mp4.mp4"))).isTrue();
	}

	@Test
	void unreadableOriginal_failsAfterThreeAttempts() throws IOException {
		ImportResponse imported = importService.importImage(admin, fixture("gone.mp4", "mpeg4", 160, 120, 10, false), false, false, null,
				"vt-fail", null);
		Files.write(storageRoot.resolve("vt-fail/gone.mp4"), "corrupted after upload".getBytes());

		for (int i = 0; i < VideoProcessingService.MAX_ATTEMPTS; i++) {
			assertThat(runner.runNextJob()).isTrue();
		}
		assertThat(runner.runNextJob()).isFalse();

		assertThat(imageRepository.findById(imported.image().id()).orElseThrow().getVideoPlayback()).isEqualTo(VideoPlayback.FAILED);
		assertThat(jobRepository.findAll()).singleElement().satisfies(job -> {
			assertThat(job.getState()).isEqualTo(VideoJob.State.FAILED);
			assertThat(job.getAttempts()).isEqualTo(3);
		});
	}

	/**
	 * With compaction on, every playback copy runs before any compaction
	 * (copies of A and B, then compaction of A and B) - and a compacted AVI
	 * becomes an HEVC .mp4 whose thumbnail and copy follow the new name.
	 */
	@Test
	void compaction_runsAfterAllCopies_andReplacesTheOriginalWithHevc() throws IOException {
		assumeTrue(x265Available, "ffmpeg without libx265 - skipping compaction");
		appSettingsService.updateCompactOriginalVideos(true);
		ImportResponse a = importService.importImage(admin, fixture("a.avi", "mjpeg", 320, 240, 25, true), false, false, null, "vt-compact", null);
		ImportResponse b = importService.importImage(admin, fixture("b.avi", "mjpeg", 320, 240, 25, true), false, false, null, "vt-compact", null);
		long aSizeBefore = Files.size(storageRoot.resolve("vt-compact/a.avi"));

		List<String> order = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			VideoJob next = jobRepository.findNextPending().orElseThrow();
			order.add(next.getStage() + ":" + next.getImageId());
			assertThat(runner.runNextJob()).isTrue();
		}
		assertThat(runner.runNextJob()).isFalse();
		assertThat(order).containsExactly(
				"PLAYBACK_COPY:" + a.image().id(), "PLAYBACK_COPY:" + b.image().id(),
				"COMPACT_ORIGINAL:" + a.image().id(), "COMPACT_ORIGINAL:" + b.image().id());

		Image compacted = imageRepository.findById(a.image().id()).orElseThrow();
		assertThat(compacted.getName()).isEqualTo("a.mp4");
		Path folder = storageRoot.resolve("vt-compact");
		assertThat(Files.exists(folder.resolve("a.avi"))).isFalse();
		VideoInfo hevc = validator.validate(folder.resolve("a.mp4"));
		assertThat(hevc.videoCodec()).isEqualTo("hevc");
		assertThat(Files.size(folder.resolve("a.mp4"))).isLessThan(aSizeBefore);
		assertThat(Files.exists(folder.resolve(".thumbnails/a.mp4.jpg"))).isTrue();
		assertThat(Files.exists(folder.resolve(".webimg/a.mp4.mp4"))).isTrue();
		assertThat(compacted.getVideoPlayback()).isEqualTo(VideoPlayback.COPY);
	}

	@Test
	void compactionOff_leavesTheOriginalAlone() throws IOException {
		importService.importImage(admin, fixture("keep.avi", "mjpeg", 160, 120, 10, false), false, false, null, "vt-nocompact", null);

		runner.runNextJob();

		assertThat(runner.runNextJob()).isFalse();
		assertThat(Files.exists(storageRoot.resolve("vt-nocompact/keep.avi"))).isTrue();
	}

	private static MockMultipartFile fixture(String name, String codec, int width, int height, int fps, boolean withAudio) throws IOException {
		String suffix = name.substring(name.lastIndexOf('.'));
		Path temp = Files.createTempFile("mpd-vt-", suffix);
		Files.delete(temp);
		try {
			List<String> command = new ArrayList<>(List.of("ffmpeg", "-y", "-f", "lavfi", "-i",
					"testsrc=duration=1:size=" + width + "x" + height + ":rate=" + fps));
			if (withAudio) {
				command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=1"));
			}
			command.addAll(List.of("-c:v", codec, "-t", "1"));
			command.addAll("mjpeg".equals(codec) ? List.of("-q:v", "2", "-pix_fmt", "yuvj420p") : List.of("-pix_fmt", "yuv420p"));
			if (withAudio) {
				command.addAll(List.of("-c:a", ".avi".equals(suffix) ? "pcm_s16le" : "aac", "-shortest"));
			}
			command.add(temp.toString());
			if (run(command.toArray(String[]::new)) == null || !Files.exists(temp)) {
				throw new IllegalStateException("Could not generate fixture " + name);
			}
			return new MockMultipartFile("file", name, "video/*", Files.readAllBytes(temp));
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	/** Output of a successful command, {@code null} if it can't run or fails. */
	private static String run(String... command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes());
			return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0 ? output : null;
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}
}
