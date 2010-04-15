package org.nutz.mvc.upload;

import java.io.BufferedOutputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;

import org.nutz.filepool.FilePool;
import org.nutz.http.Http;
import org.nutz.lang.Lang;
import org.nutz.lang.Streams;
import org.nutz.lang.Strings;
import org.nutz.lang.stream.StreamBuffer;
import org.nutz.log.Log;
import org.nutz.log.Logs;
import org.nutz.mvc.Mvcs;
import org.nutz.mvc.upload.util.BufferRing;
import org.nutz.mvc.upload.util.MarkMode;

/**
 * 采用成块写入的方式，这个逻辑比 SimpleUploading 大约快了 1 倍
 * 
 * @author zozoh(zozohtnt@gmail.com)
 */
public class FastUploading implements Uploading {

	private static Log log = Logs.getLog(FastUploading.class);

	/**
	 * 缓冲环的节点宽度，推荐 8192
	 */
	private int bufferSize;

	public FastUploading(int bufferSize) {
		this.bufferSize = bufferSize;
	}

	public Map<String, Object> parse(HttpServletRequest req, String charset, FilePool tmps)
			throws UploadException {
		if (log.isDebugEnabled())
			log.debug("FastUpload : " + Mvcs.getRequestPath(req));
		/*
		 * 创建进度对象
		 */
		UploadInfo info = Uploads.createInfo(req);
		if (log.isDebugEnabled())
			log.debug("info created");
		/*
		 * 创建参数表
		 */
		Map<String, Object> params = Uploads.createParamsMap(req);
		if (log.isDebugEnabled())
			log.debugf("Params map created - %d params", params.size());
		/*
		 * 解析边界
		 */
		String firstBoundary = "--" + Http.multipart.getBoundary(req.getContentType());
		byte[] firstBoundaryBytes = Lang.toBytes(firstBoundary.toCharArray());

		String itemEndl = "\r\n--" + Http.multipart.getBoundary(req.getContentType());
		byte[] itemEndlBytes = Lang.toBytes(itemEndl.toCharArray());
		byte[] nameEndlBytes = Lang.toBytes("\r\n\r\n".toCharArray());

		if (log.isDebugEnabled())
			log.debug("boundary: " + itemEndl);

		/*
		 * 准备缓冲环，并跳过开始标记
		 */
		MarkMode mm;
		BufferRing br;
		try {
			ServletInputStream ins = req.getInputStream();
			// 构建 3 个环节点的缓冲环
			br = new BufferRing(ins, 3, bufferSize);
			// 初始加载
			info.current = br.load();
			// 跳过开始的标记
			mm = br.mark(firstBoundaryBytes);
			// 这是不可能的，应该立即退出
			if (mm != MarkMode.FOUND) {
				if (log.isWarnEnabled())
					log.warnf("Fail to find the firstBoundary (%s) in stream, quit!", firstBoundary);
				return params;
			}
			br.skipMark();
			if (log.isDebugEnabled())
				log.debug("skip first boundary");
		}
		catch (IOException e) {
			throw Lang.wrapThrow(e);
		}

		/**
		 * ========================================================<br>
		 * 进入循环
		 */
		if (log.isDebugEnabled())
			log.debug("Reading...");
		FieldMeta meta;
		try {
			do {
				info.current = br.load();
				// 标记项目头
				mm = br.mark(nameEndlBytes);
				// 找到头的结束标志
				if (MarkMode.FOUND == mm) {
					meta = new FieldMeta(br.dumpAsString());
				}
				// 这会是整个流的结束吗？
				else if (MarkMode.STREAM_END == mm && "--\r\n".equals(br.dumpAsString())) {
					break;
				}
				// 这是不可能的，抛错
				else {
					throw new UploadInvalidFormatException("Fail to found nameEnd!");
				}

				// 作为文件读取
				if (meta.isFile()) {
					// 上传的是一个空文件
					if (Strings.isBlank(meta.getFileLocalPath())) {
						do {
							info.current = br.load();
							mm = br.mark(itemEndlBytes);
							assertStreamNotEnd(mm);
							br.skipMark();
						} while (mm == MarkMode.NOT_FOUND);
					}
					// 保存临时文件
					else {
						File tmp = tmps.createFile(meta.getFileExtension());
						OutputStream ops = null;
						try {
							ops = new BufferedOutputStream(	new FileOutputStream(tmp),
															bufferSize * 2);
							do {
								info.current = br.load();
								mm = br.mark(itemEndlBytes);
								assertStreamNotEnd(mm);
								br.dump(ops);
							} while (mm == MarkMode.NOT_FOUND);
						}
						finally {
							Streams.safeClose(ops);
						}
						params.put(meta.getName(), new TempFile(meta, tmp));
					}
				}
				// 作为提交值读取
				else {
					StreamBuffer sb = new StreamBuffer();
					do {
						info.current = br.load();
						mm = br.mark(itemEndlBytes);
						assertStreamNotEnd(mm);
						br.dump(sb.getBuffer());
					} while (mm == MarkMode.NOT_FOUND);
					params.put(meta.getName(), sb.toString(charset));
				}

			} while (mm != MarkMode.STREAM_END);
		}
		catch (Exception e) {
			throw Lang.wrapThrow(e, UploadException.class);
		}
		if (log.isDebugEnabled())
			log.debugf("...Done %dbyted readed", br.readed());
		/**
		 * 全部结束<br>
		 * ========================================================
		 */

		return params;
	}

	private static void assertStreamNotEnd(MarkMode mm) throws UploadInvalidFormatException {
		if (mm == MarkMode.STREAM_END)
			throw new UploadInvalidFormatException("Should not end stream");
	}
}