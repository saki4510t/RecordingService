package com.serenegiant.service;
/*
 * Copyright (c) 2016-2021 saki t_saki@serenegiant.com
 *
 * based on DiskLruCache on AOSP
 *
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.os.SystemClock;
import androidx.annotation.NonNull;
import android.util.Log;

import com.serenegiant.nio.CharsetsUtils;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * *****************************************************************************
 * Taken from the JB source code, can be found in:
 * libcore/luni/src/main/java/libcore/io/DiskLruCache.java
 * or direct link:
 * https://android.googlesource.com/platform/libcore/+/android-4.1.1_r1/luni/src/main/java/libcore/io/DiskLruCache.java
 * *****************************************************************************
 * <p>
 * A cache that uses a bounded amount of space on a filesystem. Each cache
 * entry has a string key and a fixed number of values. Values are byte
 * sequences, accessible as streams or files. Each value must be between {@code
 * 0} and {@code Integer.MAX_VALUE} bytes in length.
 * <p>
 * <p>The cache stores its data in a directory on the filesystem. This
 * directory must be exclusive to the cache; the cache may delete or overwrite
 * files from its directory. It is an error for multiple processes to use the
 * same cache directory at the same time.
 * <p>
 * <p>This cache limits the number of bytes that it will store on the
 * filesystem. When the number of stored bytes exceeds the limit, the cache will
 * remove entries in the background until the limit is satisfied. The limit is
 * not strict: the cache may temporarily exceed it while waiting for files to be
 * deleted. The limit does not include filesystem overhead or the cache
 * journal so space-sensitive applications should set a conservative limit.
 * <p>
 * <p>Clients call {@link #edit} to create or update the values of an entry. An
 * entry may have only one editor at one time; if a value is not available to be
 * edited then {@link #edit} will return null.
 * <ul>
 * <li>When an entry is being <strong>created</strong> it is necessary to
 * supply a full set of values; the empty value should be used as a
 * placeholder if necessary.
 * <li>When an entry is being <strong>edited</strong>, it is not necessary
 * to supply data for every value; values default to their previous
 * value.
 * </ul>
 * Every {@link #edit} call must be matched by a call to {@link Editor#commit}
 * or {@link Editor#abort}. Committing is atomic: a read observes the full set
 * of values as they were before or after the commit, but never a mix of values.
 * <p>
 * <p>Clients call {@link #get} to read a snapshot of an entry. The read will
 * observe the value at the time that {@link #get} was called. Updates and
 * removals after the call do not impact ongoing reads.
 * <p>
 * <p>This class is tolerant of some I/O errors. If files are missing from the
 * filesystem, the corresponding entries will be dropped from the cache. If
 * an error occurs while writing a cache value, the edit will fail silently.
 * Callers should handle other problems by catching {@code IOException} and
 * responding appropriately.
 */
final class TimeShiftDiskCache implements Closeable {
	private static final boolean DEBUG = false;	// FIXME 実働時はfalseにすること
	private static final String TAG =  TimeShiftDiskCache.class.getSimpleName();

	static final String JOURNAL_FILE = "journal";
	static final String JOURNAL_FILE_TMP = "journal.tmp";
	static final String MAGIC = "com.serenegiant.io.TimeShiftDiskCache";
	static final String VERSION_1 = "1";
	static final long ANY_SEQUENCE_NUMBER = -1;
	private static final String CLEAN = "CLEAN";
	private static final String DIRTY = "DIRTY";
	private static final String REMOVE = "REMOVE";
	private static final String READ = "READ";

	private static final Charset UTF_8 = CharsetsUtils.UTF8;
	private static final int IO_BUFFER_SIZE = 8 * 1024;

    /*
	 * This cache uses a journal file named "journal". A typical journal file
     * looks like this:
     *     libcore.io.TimeShiftDiskCache
     *     1
     *     100
     *     2
     *
     *     CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
     *     DIRTY 335c4c6028171cfddfbaae1a9c313c52
     *     CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
     *     REMOVE 335c4c6028171cfddfbaae1a9c313c52
     *     DIRTY 1ab96a171faeeee38496d8b330771a7a
     *     CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
     *     READ 335c4c6028171cfddfbaae1a9c313c52
     *     READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
     *
     * The first five lines of the journal form its header. They are the
     * constant string "libcore.io.TimeShiftDiskCache", the disk cache's version,
     * the application's version, the value count, and a blank line.
     *
     * Each of the subsequent lines in the file is a record of the state of a
     * cache entry. Each line contains space-separated values: a state, a key,
     * and optional state-specific values.
     *   o DIRTY lines track that an entry is actively being created or updated.
     *     Every successful DIRTY action should be followed by a CLEAN or REMOVE
     *     action. DIRTY lines without a matching CLEAN or REMOVE indicate that
     *     temporary files may need to be deleted.
     *   o CLEAN lines track a cache entry that has been successfully published
     *     and may be read. A publish line is followed by the lengths of each of
     *     its values.
     *   o READ lines track accesses for LRU.
     *   o REMOVE lines track entries that have been deleted.
     *
     * The journal file is appended to as cache operations occur. The journal may
     * occasionally be compacted by dropping redundant lines. A temporary file named
     * "journal.tmp" will be used during compaction; that file should be deleted if
     * it exists when the cache is opened.
     */

	private final File directory;
	private final File journalFile;
	private final File journalFileTmp;
	private final int appVersion;
	private final long maxSize;
	private final int valueCount;
	private final long maxDurationMs;
	private long size = 0;
	private Writer journalWriter;
	private final LinkedHashMap<Long, Entry> mEntries
			= new LinkedHashMap<>(0, 0.75f, false/*accessOrder*/);   // 挿入順
	private int redundantOpCount;

	/**
	 * To differentiate between old and current snapshots, each entry is given
	 * a sequence number each time an edit is committed. A snapshot is stale if
	 * its sequence number is not equal to its entry's sequence number.
	 */
	private long nextSequenceNumber = 0;

	/* From java.util.Arrays */
	@SuppressWarnings("unchecked")
	private static <T> T[] copyOfRange(final T[] original, final int start, final int end) {
		final int originalLength = original.length; // For exception priority compatibility.
		if (start > end) {
			throw new IllegalArgumentException();
		}
		if (start < 0 || start > originalLength) {
			throw new ArrayIndexOutOfBoundsException();
		}
		final int resultLength = end - start;
		final int copyLength = Math.min(resultLength, originalLength - start);
		final T[] result = (T[]) Array
				.newInstance(original.getClass().getComponentType(), resultLength);
		System.arraycopy(original, start, result, 0, copyLength);
		return result;
	}

	/**
	 * Returns the remainder of 'reader' as a string, closing it when done.
	 */
	private static String readFully(final Reader reader) throws IOException {
		try {
			final StringWriter writer = new StringWriter();
			char[] buffer = new char[1024];
			int count;
			while ((count = reader.read(buffer)) != -1) {
				writer.write(buffer, 0, count);
			}
			return writer.toString();
		} finally {
			reader.close();
		}
	}

	/**
	 * Returns the ASCII characters up to but not including the next "\r\n", or "\n".
	 * @throws EOFException if the stream is exhausted before the next newline character.
	 */
	private static String readAsciiLine(final InputStream in) throws IOException {
		// TODO: support UTF-8 here instead

		final StringBuilder result = new StringBuilder(80);
		for ( ; ; ) {
			int c = in.read();
			if (c == -1) {
				throw new EOFException();
			} else if (c == '\n') {
				break;
			}

			result.append((char) c);
		}
		final int length = result.length();
		if (length > 0 && result.charAt(length - 1) == '\r') {
			result.setLength(length - 1);
		}
		return result.toString();
	}

	/**
	 * Closes 'closeable', ignoring any checked exceptions. Does nothing if 'closeable' is null.
	 */
	private static void closeQuietly(final Closeable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (final RuntimeException rethrown) {
				throw rethrown;
			} catch (final Exception ignored) {
			}
		}
	}

	/**
	 * Recursively delete everything in {@code dir}.
	 */
	private static void deleteContents(@NonNull final File path) throws IOException {
		if (path.isDirectory()) {
			// pathがディレクトリの時...再帰的に削除する
			final File[] files = path.listFiles();
			if (files == null) {
				// ここには来ないはずだけど
				throw new IllegalArgumentException("not a directory:" + path);
			} else if (files.length > 0) {
				for (final File file : files) {
					// recursively delete contents
					deleteContents(file);
				}
			}
			if (!path.delete()) {
				throw new IOException("failed to delete directory:" + path);
			}
		} else {
			// pathがファイルの時
			if (!path.delete()) {
				throw new IOException("failed to delete file:" + path);
			}
		}
	}

	/**
	 * This cache uses a single background thread to evict entries.
	 */
	private final ExecutorService executorService = new ThreadPoolExecutor(0, 1,
			60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
	private final Callable<Void> cleanupCallable = new Callable<Void>() {
		@Override
		public Void call() throws Exception {
			synchronized (TimeShiftDiskCache.this) {
				if (journalWriter == null) {
					return null; // closed
				}
				trimEntries();
				if (journalRebuildRequired()) {
					rebuildJournal();
					redundantOpCount = 0;
				}
			}
			return null;
		}
	};

	/**
	 * コンストラクタ
	 * @param directory
	 * @param appVersion
	 * @param valueCount
	 * @param maxSize
	 * @param maxDurationMs
	 * @throws IOException
	 */
	private TimeShiftDiskCache(final File directory,
		final int appVersion, final int valueCount,
		final long maxSize, final long maxDurationMs) throws IOException {

		if (!directory.isDirectory()) {
			throw new IOException("specific path is not a directory");
		}
		final File dir = new File(directory, UUID.randomUUID().toString());
		if (!dir.exists()) {
			if (!dir.mkdirs()) {
				throw new IOException("failed to create dir/parent dirs");
			}
		}
		this.directory = dir;
		this.appVersion = appVersion;
		this.journalFile = new File(dir, JOURNAL_FILE);
		this.journalFileTmp = new File(dir, JOURNAL_FILE_TMP);
		this.valueCount = valueCount;
		this.maxSize = maxSize;
		this.maxDurationMs = maxDurationMs;
	}

	/**
	 * Opens the cache in {@code directory}, creating a cache if none exists there.
	 *
	 * @param directory   a writable directory
	 * @param appVersion
	 * @param valueCount  the number of values per cache entry. Must be positive.
	 * @param maxSize     the maximum number of bytes this cache should use to store
	 * @param maxDuration the maximum time as mills seconds that this cache will hold
	 * @throws IOException if reading or writing the cache directory fails
	 */
	@SuppressWarnings("ResultOfMethodCallIgnored")
	public static TimeShiftDiskCache open(final File directory,
		final int appVersion, final int valueCount,
		final long maxSize, final long maxDuration) throws IOException {

		if (maxSize <= 0) {
			throw new IllegalArgumentException("maxSize <= 0");
		}
		if (valueCount <= 0) {
			throw new IllegalArgumentException("valueCount <= 0");
		}
		if (maxDuration <= 0) {
			throw new IllegalArgumentException("maxDuration <= 0");
		}

		// prefer to pick up where we left off
		TimeShiftDiskCache cache = new TimeShiftDiskCache(directory,
			appVersion, valueCount, maxSize, maxDuration);
		if (cache.journalFile.exists()) {
			try {
				cache.readJournal();
				cache.processJournal();
				cache.journalWriter = new BufferedWriter(new FileWriter(cache.journalFile, true),
						IO_BUFFER_SIZE);
				return cache;
			} catch (final IOException journalIsCorrupt) {
//                System.logW("TimeShiftDiskCache " + directory + " is corrupt: "
//                        + journalIsCorrupt.getMessage() + ", removing");
				cache.delete();
			}
		}

		// create a new empty cache
		directory.mkdirs();
		cache = new TimeShiftDiskCache(directory, appVersion, valueCount, maxSize, maxDuration);
		cache.rebuildJournal();
		return cache;
	}

	private void readJournal() throws IOException {
		final InputStream in = new BufferedInputStream(
			new FileInputStream(journalFile), IO_BUFFER_SIZE);
		try {
			final String magic = readAsciiLine(in);
			final String version = readAsciiLine(in);
			final String appVersionString = readAsciiLine(in);
			final String valueCountString = readAsciiLine(in);
			final String blank = readAsciiLine(in);
			if (!MAGIC.equals(magic)
					|| !VERSION_1.equals(version)
					|| !Integer.toString(appVersion).equals(appVersionString)
					|| !Integer.toString(valueCount).equals(valueCountString)
					|| !"".equals(blank)) {
				throw new IOException("unexpected journal header: ["
					+ magic + ", " + version + ", "
					+ valueCountString + ", " + blank + "]");
			}

			for ( ; ; ) {
				try {
					readJournalLine(readAsciiLine(in));
				} catch (final EOFException endOfJournal) {
					break;
				}
			}
		} finally {
			closeQuietly(in);
		}
	}

	private void readJournalLine(final String line) throws IOException {
		final String[] parts = line.split(" ");
		if (parts.length < 2) {
			throw new IOException("unexpected journal line: " + line);
		}

		final long key = Long.parseLong(parts[1]);
		if (parts[0].equals(REMOVE) && parts.length == 2) {
			mEntries.remove(key);
			return;
		}

		Entry entry = mEntries.get(key);
		if (entry == null) {
			entry = new Entry(key);
			mEntries.put(key, entry);
		}

		if (parts[0].equals(CLEAN) && parts.length == 2 + valueCount) {
			entry.readable = true;
			entry.currentEditor = null;
			entry.setLengths(copyOfRange(parts, 2, parts.length));
		} else if (parts[0].equals(DIRTY) && parts.length == 2) {
			entry.currentEditor = new Editor(entry);
		} else if (parts[0].equals(READ) && parts.length == 2) {
			// this work was already done by calling mEntries.get()
		} else {
			throw new IOException("unexpected journal line: " + line);
		}
	}

	/**
	 * Computes the initial size and collects garbage as a part of opening the
	 * cache. Dirty entries are assumed to be inconsistent and will be deleted.
	 */
	private void processJournal() throws IOException {
		deleteIfExists(journalFileTmp);
		for (final Iterator<Entry> i = mEntries.values().iterator(); i.hasNext(); ) {
			final Entry entry = i.next();
			if (entry.currentEditor == null) {
				for (int t = 0; t < valueCount; t++) {
					size += entry.lengths[t];
				}
			} else {
				entry.currentEditor = null;
				for (int t = 0; t < valueCount; t++) {
					deleteIfExists(entry.getCleanFile(t));
					deleteIfExists(entry.getDirtyFile(t));
				}
				i.remove();
			}
		}
	}

	/**
	 * Creates a new journal that omits redundant information.
	 * This replaces the current journal if it exists.
	 */
	@SuppressWarnings("ResultOfMethodCallIgnored")
	private synchronized void rebuildJournal() throws IOException {
		if (journalWriter != null) {
			journalWriter.close();
		}

		final Writer writer = new BufferedWriter(
			new FileWriter(journalFileTmp), IO_BUFFER_SIZE);
		writer.write(MAGIC);
		writer.write("\n");
		writer.write(VERSION_1);
		writer.write("\n");
		writer.write(Integer.toString(appVersion));
		writer.write("\n");
		writer.write(Integer.toString(valueCount));
		writer.write("\n");
		writer.write("\n");

		for (final Entry entry : mEntries.values()) {
			if (entry.currentEditor != null) {
				writer.write(DIRTY + ' ' + entry.key + '\n');
			} else {
				writer.write(CLEAN + ' ' + entry.key + entry.getLengths() + '\n');
			}
		}

		writer.close();
		journalFileTmp.renameTo(journalFile);
		journalWriter = new BufferedWriter(new FileWriter(journalFile, true), IO_BUFFER_SIZE);
	}

	private static void deleteIfExists(final File file) throws IOException {
		if (file.exists() && !file.delete()) {
			throw new IOException();
		}
	}

	public synchronized long oldestKey() {
		return mEntries.size() > 0 ? mEntries.keySet().iterator().next() : 0;
	}

	/**
	 * Returns a snapshot of the entry named {@code key}, or null if it doesn't
	 * exist is not currently readable.
	 */
	public synchronized Snapshot get(final long key) throws IOException {
		checkNotClosed();
//		validateKey(key);
		final Entry entry = mEntries.get(key);
		if (entry == null) {
			return null;
		}

		if (!entry.readable) {
			return null;
		}

        /*
         * Open all streams eagerly to guarantee that we see a single published
         * snapshot. If we opened streams lazily then the streams could come
         * from different edits.
         */
		final InputStream[] ins = new InputStream[valueCount];
		try {
			for (int i = 0; i < valueCount; i++) {
				ins[i] = new FileInputStream(entry.getCleanFile(i));
			}
		} catch (final FileNotFoundException e) {
			// a file must have been deleted manually!
			return null;
		}

		redundantOpCount++;
		journalWriter.append(READ + ' ').append(Long.toString(key)).append('\n');
		if (journalRebuildRequired()) {
			executorService.submit(cleanupCallable);
		}

		return new Snapshot(key, entry.sequenceNumber, ins);
	}

	public synchronized Snapshot getOldest() throws IOException {
		return get(oldestKey());
	}

	/**
	 * Returns an editor for the entry named {@code key}, or null if another
	 * edit is in progress.
	 */
	public Editor edit(final long key) throws IOException {
		return edit(key, ANY_SEQUENCE_NUMBER);
	}

	private synchronized Editor edit(final long key,
		final long expectedSequenceNumber) throws IOException {

		checkNotClosed();
//		validateKey(key);
		Entry entry = mEntries.get(key);
		if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER
				&& ((entry == null) ||
					(entry.sequenceNumber != expectedSequenceNumber))) {
			return null; // snapshot is stale
		}
		if (entry == null) {
			entry = new Entry(key);
			mEntries.put(key, entry);
		} else if (entry.currentEditor != null) {
			return null; // another edit is in progress
		}

		final Editor editor = new Editor(entry);
		entry.currentEditor = editor;

		// flush the journal before creating files to prevent file leaks
		journalWriter.write(DIRTY + ' ' + key + '\n');
		journalWriter.flush();
		return editor;
	}

	/**
	 * Returns the directory where this cache stores its data.
	 */
	public File getDirectory() {
		return directory;
	}

	/**
	 * Returns the maximum number of bytes that this cache should use to store
	 * its data.
	 */
	public long maxSize() {
		return maxSize;
	}

	/**
	 * Returns the number of bytes currently being used to store the values in
	 * this cache. This may be greater than the max size if a background
	 * deletion is pending.
	 */
	public synchronized long size() {
		return size;
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	private synchronized void completeEdit(final Editor editor,
		final boolean success) throws IOException {

		final Entry entry = editor.entry;
		if (entry.currentEditor != editor) {
			throw new IllegalStateException();
		}

		// if this edit is creating the entry for the first time, every index must have a value
		if (success && !entry.readable) {
			for (int i = 0; i < valueCount; i++) {
				if (!entry.getDirtyFile(i).exists()) {
					editor.abort();
					throw new IllegalStateException("edit didn't create file " + i);
				}
			}
		}

		for (int i = 0; i < valueCount; i++) {
			final File dirty = entry.getDirtyFile(i);
			if (success) {
				if (dirty.exists()) {
					File clean = entry.getCleanFile(i);
					dirty.renameTo(clean);
					long oldLength = entry.lengths[i];
					long newLength = clean.length();
					entry.lengths[i] = newLength;
					size = size - oldLength + newLength;
				}
			} else {
				deleteIfExists(dirty);
			}
		}

		redundantOpCount++;
		entry.currentEditor = null;
		if (entry.readable | success) {
			entry.readable = true;
			journalWriter.write(CLEAN + ' ' + entry.key + entry.getLengths() + '\n');
			if (success) {
				entry.sequenceNumber = nextSequenceNumber++;
			}
		} else {
			mEntries.remove(entry.key);
			journalWriter.write(REMOVE + ' ' + entry.key + '\n');
		}

		final long limit = SystemClock.elapsedRealtime() - maxDurationMs;
		final long oldest = oldestKey();
		if ((oldest > 0) && (oldest < limit)) {
			remove(oldest);
		}

		if (size > maxSize() || journalRebuildRequired()) {
			executorService.submit(cleanupCallable);
		}
	}

	/**
	 * We only rebuild the journal when it will halve the size of the journal
	 * and eliminate at least 2000 ops.
	 */
	private boolean journalRebuildRequired() {
		final int REDUNDANT_OP_COMPACT_THRESHOLD = 2000;
		return redundantOpCount >= REDUNDANT_OP_COMPACT_THRESHOLD
				&& redundantOpCount >= mEntries.size();
	}

	/**
	 * Drops the entry for {@code key} if it exists and can be removed. Entries
	 * actively being edited cannot be removed.
	 *
	 * @return true if an entry was removed.
	 */
	public synchronized boolean remove(final long key) throws IOException {
		checkNotClosed();
//		validateKey(key);
		Entry entry = mEntries.get(key);
		if (entry == null || entry.currentEditor != null) {
			return false;
		}

		for (int i = 0; i < valueCount; i++) {
			File file = entry.getCleanFile(i);
			if (!file.delete()) {
				throw new IOException("failed to delete " + file);
			}
			size -= entry.lengths[i];
			entry.lengths[i] = 0;
		}

		redundantOpCount++;
		journalWriter.append(REMOVE + ' ').append(Long.toString(key)).append('\n');
		mEntries.remove(key);

		if (journalRebuildRequired()) {
			executorService.submit(cleanupCallable);
		}

		return true;
	}

	/**
	 * Returns true if this cache has been closed.
	 */
	public boolean isClosed() {
		return journalWriter == null;
	}

	private void checkNotClosed() {
		if (journalWriter == null) {
			throw new IllegalStateException("cache is closed");
		}
	}

	/**
	 * Force buffered operations to the filesystem.
	 */
	public synchronized void flush() throws IOException {
		checkNotClosed();
		trimEntries();
		journalWriter.flush();
	}

	/**
	 * Closes this cache. Stored values will remain on the filesystem.
	 */
	public synchronized void close() throws IOException {
		if (journalWriter == null) {
			return; // already closed
		}
		for (Entry entry : new ArrayList<Entry>(mEntries.values())) {
			if (entry.currentEditor != null) {
				entry.currentEditor.abort();
			}
		}
		trimEntries();
		journalWriter.close();
		journalWriter = null;
	}

	private void trimEntries() throws IOException {
		if (DEBUG) Log.v(TAG, "trimEntries:size=" + size);
//		while (size > maxSize) {
////		Map.Entry<Long, Entry> toEvict = mEntries.eldest();
//			final Map.Entry<Long, Entry> toEvict = mEntries.entrySet().iterator().next();
//			remove(toEvict.getKey());	// この中でsizeが再計算される
//		}
		final long limit = SystemClock.elapsedRealtime() - maxDurationMs;
		for (long key = oldestKey();
			(size > maxSize) || ((key > 0) && (key < limit));
			key = oldestKey()) {

			if (!remove(key)) {    // この中でsizeが再計算される
				// 削除されなければfalseが返ってくるのでループを中断する
				break;
			}
		}
		if (DEBUG) Log.v(TAG, "trimEntries:finished, size=" + size);
	}

	/**
	 * Closes the cache and deletes all of its stored values. This will delete
	 * all files in the cache directory including files that weren't created by
	 * the cache.
	 */
	public void delete() throws IOException {
		close();
		deleteContents(directory);
	}

	private static String inputStreamToString(final InputStream in)
		throws IOException {

		return readFully(new InputStreamReader(in, UTF_8));
	}

	/**
	 * A snapshot of the values for an entry.
	 */
	public final class Snapshot implements Closeable {
		private final long key;
		private final long sequenceNumber;
		private final InputStream[] ins;

		private Snapshot(final long key,
			final long sequenceNumber, final InputStream[] ins) {

			this.key = key;
			this.sequenceNumber = sequenceNumber;
			this.ins = ins;
		}

		/**
		 * Returns an editor for this snapshot's entry, or null if either the
		 * entry has changed since this snapshot was created or if another edit
		 * is in progress.
		 */
		public Editor edit() throws IOException {
			return TimeShiftDiskCache.this.edit(key, sequenceNumber);
		}

		/**
		 * Returns the unbuffered stream with the value for {@code index}.
		 */
		public InputStream getInputStream(final int index) {
			return ins[index];
		}

		/**
		 * Returns the string value for {@code index}.
		 */
		public String getString(final int index) throws IOException {
			return inputStreamToString(getInputStream(index));
		}

		public byte[] getBytes(final int index) throws IOException {
			return getBytes(index, null);
		}

		public int available(final int index) throws IOException {
			final InputStream in = getInputStream(index);
			return in != null ? in.available() : 0;
		}

		public int getInt(final int index) throws IOException {
			final byte[] work = new byte[4];
			final InputStream in = getInputStream(index);
			try {
				if (in.read(work) == 4) {
					final ByteBuffer buf = ByteBuffer.wrap(work);
					return buf.getInt();
				}
			} finally {
				in.close();
			}
			throw new IOException();
		}

		public long getLong(final int index) throws IOException {
			final byte[] work = new byte[8];
			final InputStream in = getInputStream(index);
			try {
				if (in.read(work) == 8) {
					final ByteBuffer buf = ByteBuffer.wrap(work);
					return buf.getLong();
				}
			} finally {
				in.close();
			}
			throw new IOException();
		}

		public float getFloat(final int index) throws IOException {
			final byte[] work = new byte[4];
			final InputStream in = getInputStream(index);
			try {
				if (in.read(work) == 4) {
					final ByteBuffer buf = ByteBuffer.wrap(work);
					return buf.getFloat();
				}
			} finally {
				in.close();
			}
			throw new IOException();
		}

		public double getDouble(final int index) throws IOException {
			final byte[] work = new byte[8];
			final InputStream in = getInputStream(index);
			try {
				if (in.read(work) == 8) {
					final ByteBuffer buf = ByteBuffer.wrap(work);
					return buf.getDouble();
				}
			} finally {
				in.close();
			}
			throw new IOException();
		}

		public byte[] getBytes(final int index, final byte[] dst)
			throws IOException {

			final byte[] buf = new byte[4096];
			byte[] result = dst;
			final InputStream in = getInputStream(index);
			try {
				int total = 0;
				int bytes;
				while ((bytes = in.read(buf)) != -1) {
					if ((result == null) || (result.length < total + bytes)) {
						final byte[] temp = new byte[
							result == null ? 4096 : result.length * 2];
						if ((total > 0) && (result != null)) {
							System.arraycopy(result, 0, temp, 0, total);
						}
						result = temp;
					}
					System.arraycopy(buf, 0, result, total, bytes);
					total += bytes;
				}
			} finally {
				in.close();
			}
			return result;
		}


		@Override
		public void close() {
			for (InputStream in : ins) {
				closeQuietly(in);
			}
		}

		public long getKey() {
			return key;
		}
	}

	/**
	 * Edits the values for an entry.
	 */
	public final class Editor {
		private final Entry entry;
		private boolean hasErrors;
		private byte[] work;

		private Editor(Entry entry) {
			this.entry = entry;
		}

		/**
		 * Returns an unbuffered input stream to read the last committed value,
		 * or null if no value has been committed.
		 */
		public InputStream newInputStream(final int index)
			throws IOException, IllegalStateException {

			synchronized (TimeShiftDiskCache.this) {
				if (entry.currentEditor != this) {
					throw new IllegalStateException();
				}
				if (!entry.readable) {
					return null;
				}
				return new FileInputStream(entry.getCleanFile(index));
			}
		}

		/**
		 * Returns the last committed value as a string, or null if no value
		 * has been committed.
		 */
		public String getString(int index) throws IOException {
			InputStream in = newInputStream(index);
			return in != null ? inputStreamToString(in) : null;
		}

		/**
		 * Returns a new unbuffered output stream to write the value at
		 * {@code index}. If the underlying output stream encounters errors
		 * when writing to the filesystem, this edit will be aborted when
		 * {@link #commit} is called. The returned output stream does not throw
		 * IOExceptions.
		 */
		public OutputStream newOutputStream(final int index)
			throws IOException, IllegalStateException {

			synchronized (TimeShiftDiskCache.this) {
				if (entry.currentEditor != this) {
					throw new IllegalStateException();
				}
				return new FaultHidingOutputStream(
					new FileOutputStream(entry.getDirtyFile(index)));
			}
		}

		/**
		 * Sets the value at {@code index} to {@code value}.
		 */
		public void set(final int index, final String value) throws IOException {
			Writer writer = null;
			try {
				writer = new OutputStreamWriter(newOutputStream(index), UTF_8);
				writer.write(value);
			} finally {
				closeQuietly(writer);
			}
		}

		/**
		 * Sets the value at {@code index} to {@code buffer}.
		 * @param index
		 * @param buffer
		 * @throws IOException
		 */
		public void set(final int index, final ByteBuffer buffer)
			throws IOException {

			set(index, buffer, 0, buffer.remaining());
		}

		/**
		 * Sets the value at {@code index} to {@code buffer}.
		 * @param index
		 * @param buffer
		 * @param offset
		 * @param size
		 * @throws IOException
		 */
		public void set(final int index,
			final ByteBuffer buffer, final int offset, final int size)
				throws IOException {

			buffer.clear();
			buffer.position(offset);
			if ((work == null) || (work.length < size)) {
				work = new byte[size];
			}
			buffer.get(work);
			final OutputStream out = newOutputStream(index);
			try {
				out.write(work, 0, size);
			} finally {
				closeQuietly(out);
			}
		}

		public void set(final int index, final int value) throws IOException {
			final byte[] work = new byte[4];
			final ByteBuffer buf = ByteBuffer.wrap(work);
			buf.putInt(value);
			buf.flip();
			final OutputStream out = newOutputStream(index);
			try {
				out.write(work, 0, 4);
			} finally {
				closeQuietly(out);
			}
		}

		public void set(final int index, final long value) throws IOException {
			final byte[] work = new byte[8];
			final ByteBuffer buf = ByteBuffer.wrap(work);
			buf.putLong(value);
			buf.flip();
			final OutputStream out = newOutputStream(index);
			try {
				out.write(work, 0, 8);
			} finally {
				closeQuietly(out);
			}
		}

		public void set(final int index, final float value) throws IOException {
			final byte[] work = new byte[4];
			final ByteBuffer buf = ByteBuffer.wrap(work);
			buf.putFloat(value);
			buf.flip();
			final OutputStream out = newOutputStream(index);
			try {
				out.write(work, 0, 4);
			} finally {
				closeQuietly(out);
			}
		}

		public void set(final int index, final double value) throws IOException {
			final byte[] work = new byte[8];
			final ByteBuffer buf = ByteBuffer.wrap(work);
			buf.putDouble(value);
			buf.flip();
			final OutputStream out = newOutputStream(index);
			try {
				out.write(work, 0, 8);
			} finally {
				closeQuietly(out);
			}
		}

		/**
		 * Commits this edit so it is visible to readers.  This releases the
		 * edit lock so another edit may be started on the same key.
		 */
		public void commit() throws IOException {
			if (hasErrors) {
				completeEdit(this, false);
				remove(entry.key); // the previous entry is stale
			} else {
				completeEdit(this, true);
			}
		}

		/**
		 * Aborts this edit. This releases the edit lock so another edit may be
		 * started on the same key.
		 */
		public void abort() throws IOException {
			completeEdit(this, false);
		}

		private class FaultHidingOutputStream extends FilterOutputStream {
			private FaultHidingOutputStream(final OutputStream out) {
				super(out);
			}

			@Override
			public void write(final int oneByte) {
				try {
					out.write(oneByte);
				} catch (final IOException e) {
					hasErrors = true;
				}
			}

			@Override
			public void write(
				@NonNull final byte[] buffer, final int offset, final int length) {

				try {
					out.write(buffer, offset, length);
				} catch (final IOException e) {
					hasErrors = true;
				}
			}

			@Override
			public void close() {
				try {
					out.close();
				} catch (final IOException e) {
					hasErrors = true;
				}
			}

			@Override
			public void flush() {
				try {
					out.flush();
				} catch (final IOException e) {
					hasErrors = true;
				}
			}
		}
	}

	private final class Entry {
		private final long key;

		/**
		 * Lengths of this entry's files.
		 */
		private final long[] lengths;

		/**
		 * True if this entry has ever been published
		 */
		private boolean readable;

		/**
		 * The ongoing edit or null if this entry is not being edited.
		 */
		private Editor currentEditor;

		/**
		 * The sequence number of the most recently committed edit to this entry.
		 */
		private long sequenceNumber;

		private Entry(long key) {
			this.key = key;
			this.lengths = new long[valueCount];
		}

		public String getLengths() throws IOException {
			final StringBuilder result = new StringBuilder();
			for (long size : lengths) {
				result.append(' ').append(size);
			}
			return result.toString();
		}

		/**
		 * Set lengths using decimal numbers like "10123".
		 */
		private void setLengths(final String[] strings) throws IOException {
			if (strings.length != valueCount) {
				throw invalidLengths(strings);
			}

			try {
				for (int i = 0; i < strings.length; i++) {
					lengths[i] = Long.parseLong(strings[i]);
				}
			} catch (final NumberFormatException e) {
				throw invalidLengths(strings);
			}
		}

		private IOException invalidLengths(final String[] strings) throws IOException {
			throw new IOException("unexpected journal line: " + Arrays.toString(strings));
		}

		public File getCleanFile(int i) {
			return new File(directory, key + "." + i);
		}

		public File getDirtyFile(int i) {
			return new File(directory, key + "." + i + ".tmp");
		}
	}
}
