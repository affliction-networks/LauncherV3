/*
 * This file is part of Technic Launcher Core.
 * Copyright (C) 2013 Syndicate, LLC
 *
 * Technic Launcher Core is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Technic Launcher Core is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * as well as a copy of the GNU Lesser General Public License,
 * along with Technic Launcher Core.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.technicpack.launchercore.util;

import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.technicpack.launchercore.exception.DownloadException;
import net.technicpack.launchercore.exception.PermissionDeniedException;

import org.apache.commons.io.IOUtils;

public class Download implements Runnable {
	private static final long TIMEOUT = 30000;

	private URL url;
	private long size = -1;
	private long downloaded = 0;
	private String outPath;
	private DownloadListener listener;
	private Result result = Result.FAILURE;
	private File outFile = null;
	private Exception exception = null;
	private String md5 = "";

	public Download(String url, String outPath) throws MalformedURLException {
		this.url = new URL(url);
		this.outPath = outPath;
	}

	public float getProgress() {
		return ((float) downloaded / size) * 100;
	}

	public Exception getException() {
		return exception;
	}

	@Override
	@SuppressWarnings("unused")
	public void run() {
		ReadableByteChannel rbc = null;
		FileOutputStream fos = null;
		try {
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setDoInput(true);
			conn.setDoOutput(false);
			System.setProperty("http.agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/535.19 (KHTML, like Gecko) Chrome/18.0.1025.162 Safari/535.19");
			conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/535.19 (KHTML, like Gecko) Chrome/18.0.1025.162 Safari/535.19");
			HttpURLConnection.setFollowRedirects(true);
			conn.setUseCaches(false);
			conn.setInstanceFollowRedirects(true);
			int response = conn.getResponseCode();
			InputStream in = getConnectionInputStream(conn);

			String eTag = conn.getHeaderField("ETag").replaceAll("^\"|\"$", "");
			if (eTag.length() == 32) {
				md5 = eTag;
			}

			size = conn.getContentLength();
			outFile = new File(outPath);
			outFile.delete();

			rbc = Channels.newChannel(in);
			fos = new FileOutputStream(outFile);

			stateChanged();

			Thread progress = new MonitorThread(Thread.currentThread(), rbc);
			progress.start();

			fos.getChannel().transferFrom(rbc, 0, size > 0 ? size : Integer.MAX_VALUE);
			in.close();
			rbc.close();
			progress.interrupt();
			if (size > 0) {
				if (size == outFile.length()) {
					result = Result.SUCCESS;
				}
			} else {
				result = Result.SUCCESS;
			}
		} catch (PermissionDeniedException e) {
			exception = e;
			result = Result.PERMISSION_DENIED;
		} catch (DownloadException e) {
			exception = e;
			result = Result.FAILURE;
		} catch (Exception e) {
			exception = e;
			e.printStackTrace();
		} finally {
			IOUtils.closeQuietly(fos);
			IOUtils.closeQuietly(rbc);
		}
	}

	protected InputStream getConnectionInputStream(final URLConnection urlconnection) throws DownloadException {
		final AtomicReference<InputStream> is = new AtomicReference<InputStream>();

		for (int j = 0; (j < 3) && (is.get() == null); j++) {
			StreamThread stream = new StreamThread(urlconnection, is);
			stream.start();
			int iterationCount = 0;
			while ((is.get() == null) && (iterationCount++ < 5)) {
				try {
					stream.join(1000L);
				} catch (InterruptedException ignore) {
				}
			}

			if (stream.permDenied.get()) {
				throw new PermissionDeniedException("Permission denied!");
			}

			if (is.get() != null) {
				break;
			}
			try {
				stream.interrupt();
				stream.join();
			} catch (InterruptedException ignore) {
			}
		}

		if (is.get() == null) {
			throw new DownloadException("Unable to download file from " + urlconnection.getURL());
		}
		return new BufferedInputStream(is.get());
	}

	private void stateChanged() {
		if (listener != null)
			listener.stateChanged(outPath, getProgress());
	}

	public void setListener(DownloadListener listener) {
		this.listener = listener;
	}

	public Result getResult() {
		return result;
	}

	public File getOutFile() {
		return outFile;
	}

	public String getMd5() {
		return md5;
	}

	private static class StreamThread extends Thread {
		private final URLConnection urlconnection;
		private final AtomicReference<InputStream> is;
		public final AtomicBoolean permDenied = new AtomicBoolean(false);

		public StreamThread(URLConnection urlconnection, AtomicReference<InputStream> is) {
			this.urlconnection = urlconnection;
			this.is = is;
		}

		@Override
		public void run() {
			try {
				is.set(urlconnection.getInputStream());
			} catch (SocketException e) {
				if (e.getMessage().equalsIgnoreCase("Permission denied: connect")) {
					permDenied.set(true);
				}
			} catch (IOException ignore) {
			}
		}
	}

	private class MonitorThread extends Thread {
		private final ReadableByteChannel rbc;
		private final Thread downloadThread;
		private long last = System.currentTimeMillis();

		public MonitorThread(Thread downloadThread, ReadableByteChannel rbc) {
			super("Download Monitor Thread");
			this.setDaemon(true);
			this.rbc = rbc;
			this.downloadThread = downloadThread;
		}

		@Override
		public void run() {
			while (!this.isInterrupted()) {
				long diff = outFile.length() - downloaded;
				downloaded = outFile.length();
				if (diff == 0) {
					if ((System.currentTimeMillis() - last) > TIMEOUT) {
						if (listener != null) {
							listener.stateChanged("Download Failed", getProgress());
						}
						try {
							rbc.close();
							downloadThread.interrupt();
						} catch (IOException ignore) {
						}
						return;
					}
				} else {
					last = System.currentTimeMillis();
				}

				stateChanged();
				try {
					sleep(50);
				} catch (InterruptedException ignore) {
					return;
				}
			}
		}
	}

	public enum Result {
		SUCCESS, FAILURE, PERMISSION_DENIED,
	}
}
