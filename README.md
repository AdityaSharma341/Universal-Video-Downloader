# Universal Video Downloader — Java Edition

A personal browser-based video downloader. The backend is built with Java 21 and Spring Boot; yt-dlp and FFmpeg are invoked as external executables. **No Python runtime or Python source code is required to run this application.**

## Important Limitations
- Supports sites that yt-dlp can access; not every website is supported.
- Does not bypass DRM, paywalls, private content, login requirements, or other access controls.
- Download only content you own or are legally authorized to save, complying with site terms and applicable law.
- The computer running Spring Boot performs the download. Devices on the same trusted Wi-Fi can save the finished file through their browser.
- Do not expose this app directly to the public internet or configure router port forwarding without proper authentication and reverse proxy security.

## Requirements
1. **Java 21 JDK** or newer (`java -version`).
2. **Maven 3.9+** or an IDE with Maven support (`mvn -version`).
3. **FFmpeg** installed and accessible on PATH (`ffmpeg -version`).
4. **yt-dlp standalone executable** installed and accessible on PATH.
   - Download the Windows release `yt-dlp.exe` from [yt-dlp releases](https://github.com/yt-dlp/yt-dlp/releases/latest).
   - If `yt-dlp.exe` is not placed in your PATH, specify its location via `YTDLP_PATH`.

## Configuration Options

Configure these options via environment variables or in `application.properties`:

| Variable / Property | Default | Description |
|---|---|---|
| `APP_TOKEN` / `app.access-token` | *(empty)* | Optional secret key required in `X-App-Token` header. Strongly recommended for LAN use. |
| `YTDLP_PATH` / `app.ytdlp-path` | `yt-dlp` | Path or command name for the `yt-dlp` executable. |
| `app.download-dir` | `downloads` | Local directory where downloaded video files and job data are stored. |
| `server.port` | `8080` | Port on which the Spring Boot web application runs. |

## Run on Windows

Open PowerShell in the project directory:

```powershell
java -version
mvn -version
$env:APP_TOKEN = "use-a-long-random-secret"
# Optional if yt-dlp.exe is not on PATH:
$env:YTDLP_PATH = "C:\\Tools\\yt-dlp.exe"
mvn spring-boot:run
```

Open `http://localhost:8080` in your web browser. For laptop-only use, `APP_TOKEN` may be left empty. For phone or network access, set a strong token.

## Use on Android / LAN on Same Wi-Fi
1. Ensure both host machine and mobile device are connected to the same trusted Wi-Fi network.
2. Start the server with `APP_TOKEN` set.
3. Run `ipconfig` in PowerShell and note your active Wi-Fi IPv4 address (e.g. `192.168.1.15`).
4. On the phone browser, open `http://<HOST-IP>:8080`.
5. Enter the access token when prompted. If Windows Firewall prompts, allow Java on Private networks only.

## API Reference

The backend exposes the following REST endpoints under `/api`:

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/config` | Retrieves runtime configuration (token requirement status, cookie file state). |
| `POST` | `/api/info` | Fetches metadata (title, uploader, thumbnail, formats) for a given video URL. |
| `POST` | `/api/download` | Initiates asynchronous background download job for a video and selected quality. |
| `DELETE` | `/api/download/{id}` | Cancels an ongoing background download job. |
| `GET` | `/api/status/{id}` | Checks progress and status of a download job. |
| `GET` | `/api/file/{id}` | Streams the completed video file as an attachment to the client. |
| `POST` | `/api/upload-cookies` | Accepts and validates a Netscape format `cookies.txt` file for authenticated downloads. |
| `DELETE` | `/api/cookies` | Deletes the currently stored `cookies.txt` file. |
| `POST` | `/api/open-browser` | Launches the host's desktop browser to solve prompts or auth checks. |

## Build a Standalone JAR

```powershell
mvn clean package
java -jar target/universal-video-downloader-1.0.0.jar
```

## Project Layout

```text
src/main/java/com/example/videodownloader/
  VideoDownloaderApplication.java   # Spring Boot entry point
  VideoController.java              # REST endpoints for detection and downloads
  CookieExtractor.java              # Chrome DevTools Protocol helper for cookie extraction
src/main/resources/
  application.properties            # Configuration settings
  static/
    index.html                      # Responsive web application interface
    style.css                       # Modern dark-mode styling
    app.js                          # Frontend client logic & progress polling
downloads/                          # Target download directory (.gitkeep)
pom.xml                             # Maven build configuration
```

Downloaded files are placed under `downloads/` on the host machine. Clean up old downloads periodically to manage disk space.
