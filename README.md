# Universal Video Downloader — Java Edition

A personal browser-based downloader. The backend is Java 21 + Spring Boot; yt-dlp and FFmpeg are invoked as external executables. **No Python runtime or Python source code is used by this application.**

## Important limitations
- Supports sites yt-dlp can access; not every website is supported.
- Does not bypass DRM, paywalls, private content, login requirements or other access controls.
- Download only content you own or are authorized to save and follow site terms and applicable law.
- The computer running Spring Boot performs the download. A phone on the same trusted Wi-Fi downloads the finished file through its browser.
- Do not expose this app to the public internet or configure router port forwarding.

## Requirements
1. Java 21 JDK.
2. Maven 3.9+ (or use an IDE with Maven support).
3. FFmpeg installed and available on PATH (`ffmpeg -version`).
4. yt-dlp standalone executable installed and available on PATH. Download the Windows `yt-dlp.exe` release from https://github.com/yt-dlp/yt-dlp/releases/latest. This is a standalone executable; Python is not needed to run this Java project. On Windows, if it is not on PATH, set `YTDLP_PATH` to its full path, e.g. `C:\\Tools\\yt-dlp.exe`.

## Run on Windows
Open PowerShell in the project folder:

```powershell
java -version
mvn -version
$env:APP_TOKEN = "use-a-long-random-secret"
# Optional if yt-dlp.exe is not on PATH:
$env:YTDLP_PATH = "C:\\Tools\\yt-dlp.exe"
mvn spring-boot:run
```

Open http://localhost:8080. For laptop-only use, you may leave APP_TOKEN empty. For phone access, use a strong token as above.

## Use on Android on same Wi-Fi
1. Keep the laptop and Android phone on the same trusted Wi-Fi network.
2. Run the server with APP_TOKEN set.
3. Run `ipconfig` on Windows and find the active Wi-Fi adapter's IPv4 address.
4. On the phone open `http://LAPTOP-IP:8080`, replacing LAPTOP-IP with that address.
5. Enter the token. If Windows Firewall prompts, allow Java only on Private networks.

## Use
Paste the video webpage URL, select Detect video, choose a quality, and start the download. When finished, press Save to this device. Android browser downloads normally appear in Downloads.

## Build a JAR
```powershell
mvn clean package
java -jar target/universal-video-downloader-1.0.0.jar
```

## Project layout
```text
src/main/java/com/example/videodownloader/
  VideoDownloaderApplication.java
  VideoController.java
src/main/resources/
  application.properties
  static/index.html
  static/style.css
  static/app.js
pom.xml
```

Downloaded files are stored under `downloads/` on the host computer. This starter project retains them until manually deleted; clean up old downloads periodically.
