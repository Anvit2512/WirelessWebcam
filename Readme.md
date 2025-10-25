# DIY Wireless Webcam using Android and Python

This project turns your Android phone into a high-quality wireless webcam for your Windows PC. It uses a native Android app to capture video, streams it as an MJPEG feed over your local Wi-Fi, and uses a Python client with OpenCV to display it. Finally, OBS Studio acts as a bridge to create a virtual webcam that can be used in any video conferencing application like Zoom, Google Meet, or Microsoft Teams.

## Features

-   **Wireless Video Streaming:** No cables needed.
-   **High-Quality, Low-Latency:** Leverage your phone's superior camera quality.
-   **Configurable:** Easily adjust resolution and JPEG quality to balance performance and clarity.
-   **Universal Compatibility:** By using OBS Virtual Camera, the feed works with virtually any conferencing or streaming software.

## System Architecture

The system consists of three main components that communicate over your local Wi-Fi network:

1.  **Android App (Sender):**
    -   Uses **CameraX** to capture video frames.
    -   Encodes each frame into a JPEG.
    -   Runs an embedded **NanoHTTPD** web server to stream the JPEGs as an MJPEG feed.

2.  **Python Client (Receiver):**
    -   Uses the **`requests`** library to connect to the MJPEG stream.
    -   Parses the stream to extract individual JPEG frames.
    -   Uses **OpenCV** to decode and display the frames in a desktop window.

3.  **OBS Studio (Virtual Webcam Bridge):**
    -   Captures the Python client's OpenCV window using a "Window Capture" source.
    -   Broadcasts this captured feed as a system-wide virtual webcam using the built-in "Virtual Camera" feature.

## Prerequisites

### Hardware
-   An Android Phone (API 21 / Android 5.0 or newer).
-   A Windows PC.
-   A Wi-Fi router to connect both devices to the same local network.

### Software
1.  **Android Studio:** To build and install the Android application.
2.  **Python 3.x:** Installed on your Windows PC. [Download Python](https://www.python.org/downloads/).
3.  **Required Python Libraries:**
    ```bash
    pip install requests opencv-python numpy
    ```
4.  **OBS Studio:** Version 26.0 or newer (includes a built-in Virtual Camera). [Download OBS Studio](https://obsproject.com/).

## How to Use

Follow these steps in order to get the system running.

### 1. Build and Run the Android App
1.  Open the Android project in Android Studio.
2.  Connect your phone to your PC via USB and enable "USB Debugging" in Developer Options.
3.  Click the "Run" button in Android Studio to build and install the app on your phone.
4.  Once installed, you can disconnect the USB cable.
5.  Open the "WirelessWebcam" app on your phone.
6.  Press the **"Start Server"** button.
7.  **Take note of the IP address and port** displayed on the screen (e.g., `http://192.168.1.100:8080/stream.mjpeg`). This is your phone's IP address.

### 2. Configure the Python Client
1.  Open the `webcam_client.py` file in a text editor.
2.  Find the line `PHONE_IP_ADDRESS = "YOUR_PHONE_IP_ADDRESS"` and replace the placeholder with the actual IP address from the Android app.

### 3. Run the Python Client
1.  Open a Command Prompt or PowerShell on your PC.
2.  Navigate to the directory where you saved `webcam_client.py`.
3.  Run the script:
    ```bash
    python webcam_client.py
    ```
4.  A new window titled **"Live Camera Feed (Phone)"** should appear on your desktop, showing the video from your phone. Keep this window open.

### 4. Configure OBS Studio
1.  Open OBS Studio.
2.  In the "Sources" dock, click the **`+`** button and select **"Window Capture"**.
3.  Name the source (e.g., "Python Webcam Feed").
4.  In the properties dialog, select the window `[python.exe]: Live Camera Feed (Phone)`.
5.  Click "OK". You should see your phone's video feed in the OBS preview.
6.  Resize and crop the source in the preview window to fit the canvas as desired.

### 5. Start the Virtual Camera
1.  In the "Controls" dock (bottom-right of OBS), click **"Start Virtual Camera"**.

### 6. Use in Your Conferencing App (Zoom, Meet, etc.)
1.  Open your desired video conferencing application (e.g., Zoom).
2.  Go to the application's video or camera settings.
3.  In the list of available cameras, select **"OBS Virtual Camera"**.
4.  You should now see your phone's camera feed!

## Configuration & Customization

You can tweak the following parameters to adjust performance and quality:

-   **Resolution:** In `MainActivity.kt`, modify `.setTargetResolution(android.util.Size(640, 480))` to your desired resolution (e.g., `1280, 720` for 720p).
-   **JPEG Quality:** In `MainActivity.kt` inside the `imageProxyToJpeg` function, change the quality value in `yuvImage.compressToJpeg(..., 85, out)`. A lower value (e.g., 60) will use less bandwidth but reduce quality. A higher value (e.g., 95) will improve quality at the cost of higher bandwidth and latency.
-   **Port:** The server port can be changed in `MainActivity.kt` and `webcam_client.py`.

## Troubleshooting

-   **Connection Timeout/Error:**
    -   Ensure your phone and PC are on the **same Wi-Fi network**.
    -   Double-check that the IP address in `webcam_client.py` is correct.
    -   Check if a firewall on your PC or network is blocking the connection on port 8080.

-   **Choppy or Laggy Video:**
    -   Lower the resolution or JPEG quality in the Android app.
    -   Move closer to your Wi-Fi router for a stronger signal.

-   **Black Screen in OBS:**
    -   Make sure the Python client window is open and not minimized.
    -   In the "Window Capture" source properties in OBS, try changing the "Capture Method".

-   **"OBS Virtual Camera" Not an Option in Other Apps:**
    -   Ensure you have clicked "Start Virtual Camera" in OBS.
    -   Try restarting the conferencing application (Zoom, Meet, etc.) after starting the virtual camera.

## License
This project is licensed under the MIT License. See the `LICENSE` file for details.
