package com.example.wirelesswebcam

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.text.Charsets

class MyWebServer(port: Int) : NanoHTTPD(port) {

    private val BOUNDARY = "frameboundary"

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "Request received: ${session.uri}")

        if (session.uri != "/stream.mjpeg") {
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                "Hello from WirelessWebcam! Open /stream.mjpeg in browser."
            )
        }

        // Pipe for continuous streaming
        val inPipe = PipedInputStream()
        val outPipe = PipedOutputStream(inPipe)

        // Writer thread that pushes multipart MJPEG into the pipe
        val writer = thread(start = true, name = "MJPEGStreamWriter") {
            writeMjpegStream(outPipe)
        }

        // Use chunked response for unknown length streaming
        val resp = newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$BOUNDARY",
            inPipe
        )

        // Live streaming headers
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        resp.addHeader("Pragma", "no-cache")
        resp.addHeader("Expires", "0")
        resp.addHeader("Connection", "close")
        resp.setGzipEncoding(false)

        // THE FIX: The setDisposeCallback method does not exist and is not needed.
        // The try-catch-finally block in writeMjpegStream handles cleanup.
        // We simply return the response object.

        return resp
    }

    private fun writeMjpegStream(output: PipedOutputStream) {
        val crlf = "\r\n".toByteArray(Charsets.US_ASCII)

        try {
            // Optional initial boundary to kick some clients
            output.write(("--$BOUNDARY\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (e: IOException) {
            Log.d(TAG, "Client disconnected before first frame: ${e.message}")
            return
        }

        var lastHash = 0

        try {
            while (!Thread.interrupted()) {
                val frame = latestJpeg.get()
                if (frame == null) {
                    Thread.sleep(10)
                    continue
                }

                val h = java.util.Arrays.hashCode(frame)
                if (h == lastHash) {
                    // No new frame; tiny nap to avoid busy loop
                    Thread.sleep(5)
                    continue
                }
                lastHash = h

                val header = buildString {
                    append("--").append(BOUNDARY).append("\r\n")
                    append("Content-Type: image/jpeg\r\n")
                    append("Content-Length: ").append(frame.size).append("\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.US_ASCII)

                output.write(header)
                output.write(frame)
                output.write(crlf)
                output.flush()
            }
        } catch (e: IOException) {
            Log.d(TAG, "MJPEG I/O (client closed?): ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "MJPEG writer error: ${e.message}", e)
        } finally {
            try { output.close() } catch (_: IOException) {}
            Log.d(TAG, "MJPEG stream output closed.")
        }
    }

    companion object {
        private const val TAG = "MyWebServer"
        // MainActivity should call: MyWebServer.latestJpeg.set(jpegBytes)
        val latestJpeg: AtomicReference<ByteArray?> = AtomicReference(null)
    }
}