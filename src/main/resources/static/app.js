const $=id=>document.getElementById(id);
let token=localStorage.getItem('vdToken')||'',tokenRequired=false,poller=null;
const headers=()=>({'Content-Type':'application/json',...(token?{'X-App-Token':token}:{})});
function err(msg){$('error').textContent=msg;$('error').classList.remove('hide')}
function clearErr(){$('error').textContent='';$('error').classList.add('hide')}
function duration(s){if(!s)return 'Duration unavailable';let h=Math.floor(s/3600),m=Math.floor(s%3600/60),sec=Math.floor(s%60);return h?`${h}h ${m}m`:`${m}m ${sec}s`}

async function init(){
    try{
        let r=await fetch('/api/config'),c=await r.json();
        tokenRequired=c.tokenRequired;
        if(tokenRequired&&!token)$('tokenDialog').showModal();
        updateCookieStatus(c.hasCookieFile, c.cookieFilePath);
    }catch(e){}
}

function updateCookieStatus(has, path){
    let el=$('cookieStatus');
    if(has){
        el.innerHTML='<span class="cookie-ok">✅ Cookie file loaded</span> <button id="deleteCookies" class="btn-small btn-muted">Remove</button>';
        $('deleteCookies').onclick=async()=>{
            await fetch('/api/cookies',{method:'DELETE',headers:headers()});
            updateCookieStatus(false,path);
        };
    } else {
        el.innerHTML='<span class="cookie-none">No cookie file — upload one or use browser method below.</span>';
    }
}

$('tokenDialog').addEventListener('close',()=>{token=$('token').value.trim();localStorage.setItem('vdToken',token)});

// Toggle cookie options panel
$('useCookies').addEventListener('change',()=>{
    $('cookieOptions').classList.toggle('hide',!$('useCookies').checked);
    $('browserPrompt').classList.add('hide');
});

// Chrome warning toggle
$('browserSelect').addEventListener('change',()=>{
    $('chromeWarn').classList.toggle('hide',$('browserSelect').value!=='chrome');
});

// File upload handlers (both in options panel and in error prompt)
function setupUpload(fileId, btnId, statusId, autoRetry){
    $(fileId).addEventListener('change',()=>{$(btnId).disabled=!$(fileId).files.length});
    $(btnId).onclick=async()=>{
        let file=$(fileId).files[0];
        if(!file)return;
        $(btnId).disabled=true;$(btnId).textContent='Uploading...';
        let form=new FormData();form.append('file',file);
        try{
            let h=token?{'X-App-Token':token}:{};
            let r=await fetch('/api/upload-cookies',{method:'POST',headers:h,body:form});
            let d=await r.json();
            if(!r.ok)throw Error(d.message||d.detail||'Upload failed');
            $(statusId).textContent='✅ '+d.message;
            $(statusId).className='hint cookie-ok';
            updateCookieStatus(true, d.path);
            if(autoRetry){
                await new Promise(r=>setTimeout(r,500));
                detectVideo();
            }
        }catch(e){
            $(statusId).textContent='❌ '+e.message;
            $(statusId).className='hint cookie-err';
        }finally{
            $(btnId).disabled=false;
            $(btnId).textContent=autoRetry?'Upload & retry':'Upload';
        }
    };
}
setupUpload('cookieFile','uploadCookies','uploadStatus',false);
setupUpload('cookieFile2','uploadCookies2','uploadStatus2',true);

// Detect video
async function detectVideo(){
    clearErr();
    let url=$('url').value.trim();
    if(!url)return err('Paste a video webpage URL first.');
    let useCookies=$('useCookies').checked;
    let browser=$('browserSelect').value;
    $('detect').disabled=true;$('detect').textContent='Detecting...';
    $('preview').classList.add('hide');$('browserPrompt').classList.add('hide');
    try{
        let r=await fetch('/api/info',{method:'POST',headers:headers(),body:JSON.stringify({url,useCookies,browser})});
        let d=await r.json();
        if(!r.ok)throw Error(d.message||d.detail||'Could not detect video.');
        if(d.needsBrowser){
            $('browserError').textContent=(d.errorDetail||'Could not access cookies.')+' Upload a cookies.txt file to fix this.';
            if(d.cookieFilePath)$('browserPrompt').querySelector('code')&&($('browserPrompt').querySelector('code').textContent=d.cookieFilePath);
            $('browserPrompt').classList.remove('hide');
            return;
        }
        $('title').textContent=d.title;
        $('details').textContent=`${d.uploader} • ${duration(d.duration)} • ${d.qualities.length?'Up to '+Math.max(...d.qualities)+'p':'Quality unavailable'}`;
        if(d.thumbnail){$('thumb').src=d.thumbnail;$('thumb').classList.remove('hide')}else $('thumb').classList.add('hide');
        for(let o of [...$('quality').options]){if(o.value==='best')continue;let cap=+o.value;o.disabled=d.qualities.length>0&&!d.qualities.some(h=>h<=cap)}
        $('quality').value='best';$('preview').classList.remove('hide');$('progress').classList.add('hide');
    }catch(e){err(e.message)}finally{$('detect').disabled=false;$('detect').textContent='Detect video'}
}
$('detect').onclick=detectVideo;

// Download
$('download').onclick=async()=>{
    clearErr();
    let useCookies=$('useCookies').checked;
    let browser=$('browserSelect').value;
    $('download').disabled=true;$('download').textContent='Starting...';
    $('progress').classList.remove('hide');$('save').classList.add('hide');
    try{
        let r=await fetch('/api/download',{method:'POST',headers:headers(),body:JSON.stringify({url:$('url').value.trim(),quality:$('quality').value,useCookies,browser})});
        let d=await r.json();
        if(!r.ok)throw Error(d.message||d.detail||'Could not start download.');
        poll(d.jobId);
    }catch(e){err(e.message);$('download').disabled=false;$('download').textContent='Try again'}
};

async function poll(id){
    clearInterval(poller);
    let check=async()=>{
        try{
            let r=await fetch('/api/status/'+id,{headers:headers()}),d=await r.json();
            if(!r.ok)throw Error(d.message||d.detail||'Status request failed');
            $('bar').style.width=d.percent+'%';
            $('percent').textContent=Math.round(d.percent)+'%';
            $('message').textContent=d.message||'Working...';
            $('state').textContent=d.state==='finished'?'Download complete':'Downloading';
            if(d.state==='finished'){
                clearInterval(poller);
                $('save').href='/api/file/'+id+(token?'?token='+encodeURIComponent(token):'');
                $('save').classList.remove('hide');
                $('download').disabled=false;$('download').textContent='Download again';
            }else if(d.state==='error'){
                clearInterval(poller);err(d.message);
                $('download').disabled=false;$('download').textContent='Try again';
            }
        }catch(e){clearInterval(poller);err(e.message);$('download').disabled=false;$('download').textContent='Try again'}
    };
    await check();poller=setInterval(check,1500);
}

init();
