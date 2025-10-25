import requests
import cv2
import numpy as np
import time

# --- Configuration ---
PHONE_IP_ADDRESS = "192.168.1.7"  # <<<<<<< IMPORTANT: Replace with your phone's actual IP
SERVER_PORT = 8080
MJPEG_STREAM_URL = f"http://{PHONE_IP_ADDRESS}:{SERVER_PORT}/stream.mjpeg"
BOUNDARY = "frameboundary" # Must match the boundary defined in MyWebServer.kt

print(f"Connecting to MJPEG stream at: {MJPEG_STREAM_URL}")

try:
    # Use stream=True to keep the connection open and read chunks continuously
    response = requests.get(MJPEG_STREAM_URL, stream=True, timeout=5) # Added timeout for initial connection
    response.raise_for_status() # Raise an exception for HTTP errors (4xx or 5xx)

    # Content-Type header should look like: multipart/x-mixed-replace;boundary=frameboundary
    content_type = response.headers.get('Content-Type', '')
    if BOUNDARY not in content_type:
        print(f"Warning: Boundary '{BOUNDARY}' not found in Content-Type header.")
        print(f"Actual Content-Type: {content_type}")
        # Attempt to proceed anyway, but this might indicate an issue with the server or URL.

    bytes_buffer = bytes() # Buffer to accumulate bytes until a full JPEG frame is found

    # Iterate over the response content in chunks
    for chunk in response.iter_content(chunk_size=1024):
        bytes_buffer += chunk

        # Look for the MJPEG boundary. The boundary separates frames.
        # It's usually preceded by CRLF and followed by more headers for the image.
        # The full boundary string is "--frameboundary\r\nContent-Type: image/jpeg\r\nContent-Length: <size>\r\n\r\n"
        # We'll look for "--frameboundary" and assume subsequent parsing handles headers.
        start_marker = b'--' + BOUNDARY.encode('ascii') + b'\r\nContent-Type: image/jpeg\r\n'
        end_marker = b'\r\n--' + BOUNDARY.encode('ascii') # This marks the end of a frame's data block

        while start_marker in bytes_buffer:
            # Find the start of the current JPEG frame's data
            start_index = bytes_buffer.find(start_marker)
            if start_index == -1:
                break # Not enough data for a new frame yet

            # Extract the part of the buffer after the start marker
            frame_data_part = bytes_buffer[start_index + len(start_marker):]

            # Find the Content-Length header to determine the size of the JPEG data
            content_length_start = frame_data_part.find(b'Content-Length: ')
            if content_length_start == -1:
                # Not enough data for Content-Length header yet, or malformed
                break

            content_length_end = frame_data_part.find(b'\r\n', content_length_start)
            if content_length_end == -1:
                break # Not enough data for Content-Length value yet

            try:
                content_length_str = frame_data_part[content_length_start + len(b'Content-Length: '):content_length_end].decode('ascii')
                jpeg_size = int(content_length_str)
            except ValueError:
                print("Error: Could not parse Content-Length.")
                bytes_buffer = bytes_buffer[start_index + len(start_marker) + content_length_end:] # Skip problematic part
                continue # Try to find next boundary

            # The actual JPEG data starts after the double CRLF following Content-Length
            jpeg_data_start = content_length_end + len(b'\r\n\r\n')
            if jpeg_data_start > len(frame_data_part):
                break # Not enough data for the actual JPEG bytes yet

            # Check if we have enough data for the full JPEG frame
            if (jpeg_data_start + jpeg_size) > len(frame_data_part):
                break # Not enough JPEG data received yet

            # Extract the raw JPEG bytes
            jpeg_bytes = frame_data_part[jpeg_data_start : jpeg_data_start + jpeg_size]

            # Decode and display the frame using OpenCV
            np_arr = np.frombuffer(jpeg_bytes, dtype=np.uint8)
            frame = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)

            if frame is not None:
                cv2.imshow('Live Camera Feed (Phone)', frame)
                # waitKey(1) keeps the window open and processes events
                # It returns the key pressed, or -1 if no key was pressed.
                # If 'q' is pressed, exit.
                if cv2.waitKey(1) & 0xFF == ord('q'):
                    break
            else:
                print("Warning: Failed to decode JPEG frame.")

            # Update the buffer to remove the processed frame and look for the next one
            # Find the end of this current frame's part, which is usually another boundary.
            # We skip past the start_index of the *current* full frame that was just processed.
            bytes_buffer = bytes_buffer[start_index + len(start_marker) + jpeg_data_start + jpeg_size + len(b'\r\n'):] # Skip the processed frame + trailing CRLF
            # The next boundary should be in the remaining bytes_buffer

    print("Stream ended or 'q' pressed.")

except requests.exceptions.Timeout:
    print(f"Error: Connection to {MJPEG_STREAM_URL} timed out after 5 seconds.")
except requests.exceptions.ConnectionError as e:
    print(f"Error: Could not connect to the server at {MJPEG_STREAM_URL}.")
    print(f"Please ensure the Android app is running, server is started, and IP address '{PHONE_IP_ADDRESS}' is correct.")
    print(f"Details: {e}")
except requests.exceptions.RequestException as e:
    print(f"An unexpected error occurred: {e}")
finally:
    cv2.destroyAllWindows() # Close all OpenCV windows when done
    print("Client application exited.")