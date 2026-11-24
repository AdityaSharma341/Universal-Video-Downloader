const $ = id => document.getElementById(id);
let token = localStorage.getItem('vdToken') || '', tokenRequired = false, poller = null;
let currentMode = 'video';
let detectedQualities = [];

const headers = () => ({
    'Content-Type': 'application/json',
    ...(token ? { 'X-App-Token': token } : {})
});

function err(msg) { $('error').textContent = msg; $('error').classList.remove('hide'); }
function clearErr() { $('error').textContent = ''; $('error').classList.add('hide'); }

function duration(s) {
    if (!s) return 'Duration unavailable';
    let h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), sec = Math.floor(s % 60);
    return h ? `${h}h ${m}m` : `${m}m ${sec}s`;
}

function resLabel(h) {
    if (h >= 2160) return h + 'p (4K)';
    if (h >= 1440) return h + 'p (2K)';
    if (h >= 1080) return h + 'p (Full HD)';
    if (h >= 720) return h + 'p (HD)';
    return h + 'p';
}

// ── Init ───────────────────────────────────────────────────────────────

async function init() {
    try {
        let r = await fetch('/api/config'), c = await r.json();
        tokenRequired = c.tokenRequired;
        if (tokenRequired && !token) $('tokenDialog').showModal();
        updateCookieIndicator(c.hasCookieFile);
    } catch (e) {}
}

function updateCookieIndicator(has) {
    let el = $('cookieIndicator');
    if (has) {
        el.innerHTML = '🍪 <span class="cookie-ok">Cookie file loaded</span>';
        $('deleteCookies').classList.remove('hide');
    } else {
        el.innerHTML = '🔓 <span class="cookie-auto">Using browser cookies automatically</span>';
        $('deleteCookies').classList.add('hide');
    }
}

// ── Token dialog ───────────────────────────────────────────────────────

$('tokenDialog').addEventListener('close', () => {
    token = $('token').value.trim();
    localStorage.setItem('vdToken', token);
});

// ── Cookie management (collapsed by default) ───────────────────────────

$('cookieToggle').onclick = () => {
    $('cookiePanel').classList.toggle('hide');
    $('cookieToggle').textContent = $('cookiePanel').classList.contains('hide') ? 'Manage cookies' : 'Hide';
};

// Cookie file upload
$('cookieFile').addEventListener('change', () => {
    $('uploadCookies').disabled = !$('cookieFile').files.length;
});

$('uploadCookies').onclick = async () => {
    let file = $('cookieFile').files[0];
    if (!file) return;
    $('uploadCookies').disabled = true;
    $('uploadCookies').textContent = 'Uploading...';
    let form = new FormData();
    form.append('file', file);
    try {
        let h = token ? { 'X-App-Token': token } : {};
        let r = await fetch('/api/upload-cookies', { method: 'POST', headers: h, body: form });
        let d = await r.json();
        if (!r.ok) throw Error(d.message || d.detail || 'Upload failed');
        $('uploadStatus').textContent = '✅ ' + d.message;
        $('uploadStatus').className = 'hint cookie-ok';
        updateCookieIndicator(true);
    } catch (e) {
        $('uploadStatus').textContent = '❌ ' + e.message;
        $('uploadStatus').className = 'hint cookie-err';
    } finally {
        $('uploadCookies').disabled = false;
        $('uploadCookies').textContent = 'Upload';
    }
};

// Delete cookie file
$('deleteCookies').onclick = async () => {
    await fetch('/api/cookies', { method: 'DELETE', headers: headers() });
    updateCookieIndicator(false);
    $('uploadStatus').textContent = 'Cookie file removed.';
    $('uploadStatus').className = 'hint';
};

// ── Mode toggle (Video / Audio) ────────────────────────────────────────

function setMode(mode) {
    currentMode = mode;
    document.querySelectorAll('.mode-btn').forEach(b => {
        b.classList.toggle('active', b.dataset.mode === mode);
    });
    if (mode === 'audio') {
        $('qualitySection').classList.add('hide');
        $('audioSection').classList.remove('hide');
    } else {
        $('qualitySection').classList.remove('hide');
        $('audioSection').classList.add('hide');
    }
}

$('modeVideo').onclick = () => setMode('video');
$('modeAudio').onclick = () => setMode('audio');

// ── Build quality dropdown from detected resolutions ───────────────────

function buildQualityDropdown(qualities) {
    detectedQualities = qualities;
    let sel = $('quality');
    sel.innerHTML = '';

    // "Best available" option always first
    let bestOpt = document.createElement('option');
    bestOpt.value = 'best';
    bestOpt.textContent = 'Best available' + (qualities.length ? ' (' + Math.max(...qualities) + 'p)' : '');
    sel.appendChild(bestOpt);

    // Add each detected resolution
    for (let h of qualities) {
        let opt = document.createElement('option');
        opt.value = String(h);
        opt.textContent = resLabel(h);
        sel.appendChild(opt);
    }
}

// ── Detect video ───────────────────────────────────────────────────────

async function detectVideo() {
    clearErr();
    let url = $('url').value.trim();
    if (!url) return err('Paste a video webpage URL first.');
    let browser = $('browserSelect').value;
    $('detect').disabled = true;
    $('detect').textContent = 'Detecting...';
    $('preview').classList.add('hide');
    try {
        let r = await fetch('/api/info', {
            method: 'POST',
            headers: headers(),
            body: JSON.stringify({ url, browser })
        });
        let d = await r.json();
        if (!r.ok) throw Error(d.message || d.detail || 'Could not detect video.');
        $('title').textContent = d.title;
        let maxQ = d.qualities.length ? Math.max(...d.qualities) + 'p' : 'Quality unavailable';
        let audioTag = d.hasAudio ? '' : ' · No audio track';
        $('details').textContent = `${d.uploader} • ${duration(d.duration)} • Up to ${maxQ}${audioTag}`;
        if (d.thumbnail) {
            $('thumb').src = d.thumbnail;
            $('thumb').classList.remove('hide');
        } else {
            $('thumb').classList.add('hide');
        }

        // Build the quality options dynamically from detected resolutions
        buildQualityDropdown(d.qualities);

        // Show or hide audio option based on whether audio tracks exist
        if (d.hasAudio) {
            $('modeAudio').classList.remove('hide');
        } else {
            $('modeAudio').classList.add('hide');
            setMode('video');
        }

        // Reset to video mode
        setMode('video');

        $('preview').classList.remove('hide');
        $('progress').classList.add('hide');
    } catch (e) {
        err(e.message);
    } finally {
        $('detect').disabled = false;
        $('detect').textContent = 'Detect video';
    }
}

$('detect').onclick = detectVideo;

// Also detect on Enter key in URL input
$('url').addEventListener('keydown', e => {
    if (e.key === 'Enter') { e.preventDefault(); detectVideo(); }
});

// ── Download ───────────────────────────────────────────────────────────

async function startDownload() {
    clearErr();
    let mode = currentMode;
    let quality = mode === 'audio' ? 'best' : $('quality').value;
    let browser = $('browserSelect').value;
    let btn = mode === 'audio' ? $('downloadAudio') : $('download');

    btn.disabled = true;
    btn.textContent = 'Starting...';
    $('progress').classList.remove('hide');
    $('save').classList.add('hide');

    try {
        let r = await fetch('/api/download', {
            method: 'POST',
            headers: headers(),
            body: JSON.stringify({
                url: $('url').value.trim(),
                quality,
                mode,
                browser
            })
        });
        let d = await r.json();
        if (!r.ok) throw Error(d.message || d.detail || 'Could not start download.');
        poll(d.jobId);
    } catch (e) {
        err(e.message);
        btn.disabled = false;
        btn.textContent = 'Try again';
    }
}

$('download').onclick = startDownload;
$('downloadAudio').onclick = startDownload;

// ── Poll progress ──────────────────────────────────────────────────────

async function poll(id) {
    clearInterval(poller);
    let check = async () => {
        try {
            let r = await fetch('/api/status/' + id, { headers: headers() }), d = await r.json();
            if (!r.ok) throw Error(d.message || d.detail || 'Status request failed');
            $('bar').style.width = d.percent + '%';
            $('percent').textContent = Math.round(d.percent) + '%';
            $('message').textContent = d.message || 'Working...';
            let isAudio = currentMode === 'audio';
            if (d.state === 'finished') {
                $('state').textContent = isAudio ? 'Audio ready' : 'Download complete';
            } else if (d.state === 'processing') {
                $('state').textContent = isAudio ? 'Converting' : 'Processing';
            } else {
                $('state').textContent = isAudio ? 'Downloading audio' : 'Downloading';
            }

            if (d.state === 'finished') {
                clearInterval(poller);
                $('save').href = '/api/file/' + id + (token ? '?token=' + encodeURIComponent(token) : '');
                $('save').classList.remove('hide');
                $('save').textContent = isAudio ? 'Save audio to this device' : 'Save to this device';
                resetDownloadButtons();
            } else if (d.state === 'error') {
                clearInterval(poller);
                err(d.message);
                resetDownloadButtons();
            }
        } catch (e) {
            clearInterval(poller);
            err(e.message);
            resetDownloadButtons();
        }
    };
    await check();
    poller = setInterval(check, 1500);
}

function resetDownloadButtons() {
    $('download').disabled = false;
    $('download').textContent = 'Download';
    $('downloadAudio').disabled = false;
    $('downloadAudio').textContent = 'Download audio';
}

// ── Start ──────────────────────────────────────────────────────────────

init();
