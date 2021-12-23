# LiteCCTV

Lightweight CCTV. Convert your android devices into CCTV.

There's also the super lite version <a href="https://github.com/ADryInkCartridge/LiteCCTVvery">Super Lite CCTV</a> for Android API Level under 21.

## How does this application work?
This is how the application works.
1. Application captures image from camera every 0.9 second.
2. If there's motion detected from the image, the application sends the image to <a href="https://github.com/ADryInkCartridge/LiteCCTVAPI">LiteCCTV API Web Server</a>.
3. The API Web Server will analyze the image for face recognition and emotion prediction.

## References
1. <a href="https://developer.android.com/codelabs/camerax-getting-started">Getting Started with CameraX Android</a>
2. <a href="https://developer.android.com/training/volley">Volley Library Documentation</a>
3. <a href="https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.concurrent/thread.html">Thread in Kotlin</a>
