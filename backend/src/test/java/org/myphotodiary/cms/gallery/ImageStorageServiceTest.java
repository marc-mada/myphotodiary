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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ImageStorageService} exercised directly, without Spring/JPA/HSQLDB -
 * deliberately, for the one test in this file: a real-thread reproduction of
 * a concurrency bug (see below) large/slow enough per call that HSQLDB's own
 * transaction locking (confirmed live, 25/09/2026 - background threads each
 * blocked in turn on {@code org.hsqldb.lib.CountUpDownLatch} inside {@code
 * Session.executeCompiledStatement}) would otherwise serialize the very
 * concurrency this test exists to create, masking the bug behind a 30s+
 * timeout rather than reproducing it. A plain {@code new
 * ImageStorageService(...)} sidesteps that layer entirely - this class is
 * pure filesystem I/O, no database involved in its own real behavior either.
 */
class ImageStorageServiceTest {

	@TempDir
	Path storageRoot;

	@Test
	void concurrentEditCommitsOnTheSameImage_dontRaceEachOtherOffTheSharedTempPath() throws Exception {
		// Real bug, root-caused 25/09/2026 from a live report ("the transform
		// feature sometimes... spreads strong an area while preserving the
		// rest of the image") after every plausible warp-math cause had
		// already been ruled out by direct testing (PerspectiveTransform,
		// bilinearSample, portrait/landscape, synthetic/real photo content -
		// all clean). The actual cause was one layer down: rotateInPlace/
		// cropInPlace/transformImage each used to derive their atomic
		// write-then-move temp path from the image's own name ALONE (e.g.
		// ".transforming-<name>"), with no per-request uniqueness - so two
		// overlapping edit-commit requests on the SAME image (a
		// double-clicked Save, a slow-network browser retry, two tabs open
		// on the same photo) would both write to that identical OS path at
		// once, then race each other's Files.move over it.
		//
		// Confirmed directly (not guessed) before this fix: N raw threads
		// all committing a transform to the same file at once reliably threw
		// NoSuchFileException moving the shared temp path out from under a
		// thread that had already had it moved away by another - the
		// reported "corrupts part of the image, rest looks plausible" is
		// consistent with the same unsynchronized-concurrent-write-to-one-
		// path mechanism landing less cleanly (a still-decodable but
		// partially overwritten JPEG) depending on filesystem/timing, rather
		// than always a clean exception.
		//
		// Fixed via uniqueTempSibling (a random UUID per call) - this test
		// asserts every concurrent commit either succeeds outright or fails
		// with a real, pre-existing, independent failure mode (a genuinely
		// degenerate quad - deliberately included among the per-thread
		// quads below, to prove this test doesn't just accept "some threads
		// silently swallowed"), but NEVER the NoSuchFileException/file-
		// corruption failure mode this bug produced - and that the file left
		// on disk afterwards always decodes cleanly at the expected size.
		String directoryPath = "2026/08/race";
		Path directory = storageRoot.resolve(directoryPath);
		Files.createDirectories(directory);
		String imageName = "race-target.jpg";
		writeSyntheticJpeg(directory.resolve(imageName), 3000, 4000);

		StorageProperties properties = new StorageProperties();
		properties.setRoot(storageRoot.toString());
		ImageStorageService storage = new ImageStorageService(properties, new VideoThumbnailExtractor("ffmpeg"));

		int w = 3000, h = 4000;
		int threadCount = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Void>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < threadCount; i++) {
				// Distinct quads per thread, all but the last two well
				// within the legitimate range already proven clean
				// elsewhere (GalleryServiceTest's own dragged-corner
				// tests) - the last two (shrink 53%/61% against this
				// particular rectangle) are a genuinely degenerate quad
				// independent of concurrency (confirmed identical with the
				// race fix applied, run repeatedly), included deliberately
				// so this test can tell "the real bug's failure mode" apart
				// from "an unrelated, expected failure mode" rather than
				// simply weakening every thread's quad to always succeed.
				int shrink = 5 + i * 8;
				double[] quadX = {0, w, w - (double) (w * shrink / 100), 0};
				double[] quadY = {0, 0, h - (double) (h * shrink / 100), h};
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					storage.transformImage(directoryPath, imageName, w, h, quadX, quadY);
					return null;
				}));
			}
			ready.await();
			go.countDown();

			for (Future<Void> future : futures) {
				try {
					future.get(30, TimeUnit.SECONDS);
				} catch (java.util.concurrent.ExecutionException e) {
					assertThat(e.getCause())
							.as("a concurrent commit must never fail with the shared-temp-path race's own failure mode")
							.isInstanceOf(IllegalArgumentException.class)
							.hasMessageContaining("Degenerate quad");
				}
			}
		} finally {
			pool.shutdownNow();
		}

		BufferedImage result = ImageIO.read(directory.resolve(imageName).toFile());
		assertThat(result).isNotNull();
		assertThat(result.getWidth()).isEqualTo(w);
		assertThat(result.getHeight()).isEqualTo(h);
	}

	private static void writeSyntheticJpeg(Path target, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.LIGHT_GRAY);
		g.fillRect(0, 0, width, height);
		g.setColor(Color.RED);
		g.fillRect(0, 0, width / 2, height / 2);
		g.setColor(Color.BLUE);
		g.fillRect(width / 2, height / 2, width / 2, height / 2);
		g.dispose();
		ImageIO.write(image, "jpg", target.toFile());
	}
}
