package com.duowan.mobile.netroid.request;

import android.text.TextUtils;
import com.duowan.mobile.netroid.*;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.protocol.HTTP;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * Its purpose is provide a big file download impmenetation, suport continuous transmission
 * on the breakpoint download if server-side enable 'Content-Range' Header.
 * for example:
 * 		execute a request and submit header like this : Range=bytes=1000- (1000 means the begin point of the file).
 * 		response return a header like this Content-Range=bytes 1000-1895834/1895835, that's continuous transmission,
 * 		also return Accept-Ranges=bytes tell us the server-side supported range transmission.
 *
 * This request will stay longer in the thread which dependent your download file size,
 * that will fill up your thread poll as soon as possible if you open many request,
 * if all threads is busy, the high priority request such as {@link StringRequest}
 * might waiting long time, so don't use it alone.
 * we highly recommend you to use it with the {@link com.duowan.mobile.netroid.toolbox.FileDownloader},
 * FileDownloader maintain a download task queue, let's set the maximum parallel request count, the rest will await.
 *
 * By the way, this request priority was {@link Priority#LOW}, higher request will jump ahead it.
 */
public class FileDownloadRequest extends Request<Void> {
	private File mStoreFile;
	private File mTemporaryFile;

	public FileDownloadRequest(String storeFilePath, String url) {
		super(url, null);
		mStoreFile = new File(storeFilePath);
		mTemporaryFile = new File(storeFilePath + ".tmp");

		// Note: if the request header "Range" greater than the actual length that server-size have,
		// the response header "Content-Range" will return "bytes */[actual length]", that's wrong.
		addHeader("Range", "bytes=" + mTemporaryFile.length() + "-");
	}

	/** Ignore the response content, just rename the TemporaryFile to StoreFile. */
	@Override
	protected Response<Void> parseNetworkResponse(NetworkResponse response) {
		if (!isCanceled()) {
			if (mTemporaryFile.canRead() && mTemporaryFile.length() > 0) {
				if (mTemporaryFile.renameTo(mStoreFile)) {
					return Response.success(null, response);
				} else {
					return Response.error(new NetroidError("Can't rename the download temporary file!"));
				}
			} else {
				return Response.error(new NetroidError("Download temporary file was invalid!"));
			}
		}
		return Response.error(new NetroidError("Request was Canceled!"));
	}

	/**
	 * In this method, we got the Content-Length, with the TemporaryFile length,
	 * we can calculate the actually size of the whole file, if TemporaryFile not exists,
	 * we'll take the store file length then compare to actually size, and if equals,
	 * we consider this download was already done.
	 * We used {@link RandomAccessFile} to continue download, when download success,
	 * the TemporaryFile will be rename to StoreFile.
	 */
	@Override
	public byte[] handleResponse(HttpResponse response, Delivery delivery) throws IOException, ServerError {
		// The file actually size.
		long fileSize = Long.parseLong(HttpUtils.getHeader(response, HTTP.CONTENT_LEN));
		long downloadedSize = mTemporaryFile.length();

		boolean isSupportRange = HttpUtils.isSupportRange(response);
		if (isSupportRange) {
			fileSize += downloadedSize;

			// Verify the Content-Range Header, to ensure temporary file is part of the whole file.
			// Sometime, temporary file length add response content-length might greater than actual file length,
			// in this situation, we consider the temporary file is invalid, then throw an exception.
			String realRangeValue = HttpUtils.getHeader(response, "Content-Range");
			// response Content-Range may be null when "Range=bytes=0-"
			if (!TextUtils.isEmpty(realRangeValue)) {
				String assumeRangeValue = "bytes " + downloadedSize + "-" + (fileSize - 1);
				if (TextUtils.indexOf(realRangeValue, assumeRangeValue) == -1) {
					throw new IllegalStateException(
							"The Content-Range Header is invalid Assume[" + assumeRangeValue + "] vs Real[" + realRangeValue + "], " +
									"please remove the temporary file [" + mTemporaryFile + "].");
				}
			}
		}

		// Don't go on if fileSize illegal.
		if (fileSize < 1) throw new IOException("Response's Empty!");

		// Compare the store file size(after download successes have) to server-side Content-Length.
		// temporary file will rename to store file after download success, so we compare the
		// Content-Length to ensure this request already download or not.
		if (mStoreFile.length() == fileSize) {
			// Rename the store file to temporary file, mock the download success. ^_^
			mStoreFile.renameTo(mTemporaryFile);

			// Deliver download progress.
			delivery.postDownloadProgress(this, fileSize, fileSize);

			return null;
		}

		RandomAccessFile tmpFileRaf = new RandomAccessFile(mTemporaryFile, "rw");

		// If server-side support range download, we seek to last point of the temporary file.
		if (isSupportRange) {
			tmpFileRaf.seek(downloadedSize);
		} else {
			// If not, truncate the temporary file then start download from beginning.
			tmpFileRaf.setLength(0);
			downloadedSize = 0;
		}

		HttpEntity entity = null;
		try {
			entity = response.getEntity();
			InputStream inStream = entity.getContent();
			byte[] buffer = new byte[8 * 1024]; // 8K buffer
			int offset;

			while ((offset = inStream.read(buffer)) != -1) {
				tmpFileRaf.write(buffer, 0, offset);

				downloadedSize += offset;
				delivery.postDownloadProgress(this, fileSize, downloadedSize);

				if (isCanceled()) {
					delivery.postCancel(this);
					break;
				}
			}
		} finally {
			try {
				// Close the InputStream and release the resources by "consuming the content".
				if (entity != null) entity.consumeContent();
			} catch (Exception e) {
				// This can happen if there was an exception above that left the entity in
				// an invalid state.
				NetroidLog.v("Error occured when calling consumingContent");
			}
			tmpFileRaf.close();
		}

		return null;
	}

	@Override
	public Priority getPriority() {
		return Priority.LOW;
	}

	/** Never use cache in this case. */
	@Override
	public void setCacheSequence(int... cacheSequence) {
	}

}