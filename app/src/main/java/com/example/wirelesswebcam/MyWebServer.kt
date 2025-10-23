package com.example.wirelesswebcam

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import kotlin.text.Charsets

class MyWebServer(port: Int) : NanoHTTPD(port) {

    private val BOUNDARY = "frameboundary"

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "Request received: ${session.uri}")

        return if (session.uri == "/stream.mjpeg") {
            // Create a PipedInputStream which will be passed to the response.
            val pipedInputStream = PipedInputStream()
            // Create a PipedOutputStream that is connected to the PipedInputStream.
            // We will write our JPEG frames to this stream in a background thread.
            val pipedOutputStream = PipedOutputStream(pipedInputStream)

            // Start a background thread to write the MJPEG stream to the PipedOutputStream.
            // The PipedInputStream will then feed this data to the HTTP response.
            thread(start = true, name = "MJPEGStreamWriterThread") {
                writeMjpegStream(pipedOutputStream)
            }

            // Create the response with the input stream.
            // For MJPEG, we use newFixedLengthResponse with length -1 (unknown length)
            // and explicitly set the input stream. This tells NanoHTTPD to stream raw data
            // from the InputStream without adding its own chunked encoding.
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=$BOUNDARY",
                pipedInputStream,
                -1 // -1 signifies unknown length for streaming
            )

            // These headers are still useful for live streaming.
            response.setKeepAlive(true)
            response.setGzipEncoding(false) // MJPEG images are already compressed

            response

        } else {
            newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                "Hello from WirelessWebcam! Open /stream.mjpeg in browser."
            )
        }
    }

    private fun writeMjpegStream(outputStream: PipedOutputStream) {
        val crlfBytes = "\r\n".toByteArray(Charsets.US_ASCII)

        try {
            while (!Thread.interrupted()) {
                val frame = frames.take() // Blocks until a frame is available

                // Write MJPEG boundary and headers
                // Note: The header string must be constructed for each frame,
                // as Content-Length changes per frame.
                val header = "--$BOUNDARY\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.size}\r\n\r\n" // Double CRLF to end headers

                outputStream.write(header.toByteArray(Charsets.US_ASCII))
                outputStream.write(frame)
                outputStream.write(crlfBytes) // End of frame with CRLF before next boundary

                outputStream.flush()
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "MJPEG stream writer interrupted: ${e.message}")
            Thread.currentThread().interrupt() // Restore interrupt status
        } catch (e: IOException) {
            // This is typically how we detect client disconnects (e.g., "Pipe broken")
            Log.d(TAG, "MJPEG stream I/O error (client likely disconnected): ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in MJPEG stream writer: ${e.message}")
        } finally {
            try {
                outputStream.close()
                Log.d(TAG, "MJPEG stream PipedOutputStream closed.")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing MJPEG PipedOutputStream: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "MyWebServer"
        // Increased queue size. A capacity of 2-5 is often a good balance.
        // It provides a small buffer without introducing too much lag or memory usage.
        val frames: ArrayBlockingQueue<ByteArray> = ArrayBlockingQueue(2)
    }
}
//
