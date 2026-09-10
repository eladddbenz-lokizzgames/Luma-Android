(function(){
  const pending=new Map();
  const oldTyping=typeof typing==='function'?typing:null;

  function isNative(){try{return !!window.LumaNative}catch(e){return false}}
  function provider(){try{return isNative()?LumaNative.getAiProvider():'Fast AI unavailable'}catch(e){return 'Fast AI unavailable'}}
  function hasKey(){try{return isNative()&&LumaNative.hasAiKey()}catch(e){return false}}
  function requestId(){return 'r'+Date.now().toString(36)+Math.random().toString(36).slice(2,8)}

  function buildMessages(prompt){
    const c=typeof active==='function'?active():null;
    let screen='';
    try{if(isNative()&&LumaNative.isScreenSharing())screen=LumaNative.getScreenContext()||''}catch(e){}
    const system='You are Luma, a fast and capable Android AI assistant. Answer directly and clearly. For difficult requests, reason carefully before answering.'+
      (screen?' The user explicitly enabled Live Screen. Current screen context: '+screen:'');
    let history=(c&&Array.isArray(c.messages)?c.messages:[])
      .filter(m=>m.kind==='text'&&(m.role==='user'||m.role==='assistant'))
      .slice(-16)
      .map(m=>({role:m.role==='assistant'?'assistant':'user',content:String(m.text||'')}));
    const p=String(prompt||'').trim();
    if(!history.length||history[history.length-1].role!=='user'||history[history.length-1].content.trim()!==p){
      history.push({role:'user',content:p});
    }
    return [{role:'system',content:system},...history];
  }

  window.onNativeChatResult=function(id,ok,text){
    const item=pending.get(id); if(!item)return;
    pending.delete(id); clearTimeout(item.timer);
    if(ok)item.resolve(String(text||''));
    else{
      if(String(text||'')==='SETUP_REQUIRED')openAiSetup();
      item.reject(new Error(String(text||'Fast AI request failed')));
    }
  };

  function nativeChat(prompt){
    return new Promise((resolve,reject)=>{
      if(!isNative()){reject(new Error('Native AI bridge unavailable'));return}
      const id=requestId();
      const timer=setTimeout(()=>{pending.delete(id);reject(new Error('The AI did not answer within 22 seconds.'))},22000);
      pending.set(id,{resolve,reject,timer});
      try{LumaNative.startChat(id,JSON.stringify(buildMessages(prompt)))}catch(e){clearTimeout(timer);pending.delete(id);reject(e)}
    });
  }

  function looksLikeDeviceCommand(text){
    const s=String(text||'').trim();
    return /^(please\s+)?((can|could|would)\s+you\s+)?(open|launch|click|tap|press|go\s+back|go\s+home|back|home)\b/i.test(s) ||
      /\b(open|launch)\b.+\b(then|and)\s+(click|tap|press)\b/i.test(s);
  }

  window.generateChat=async function(prompt){
    const p=String(prompt||'').trim();
    if(looksLikeDeviceCommand(p)&&isNative()){
      return String(LumaNative.runOrEnableDeviceTask(p)||'Starting Device Control…');
    }
    if(!hasKey()){
      openAiSetup();
      throw new Error('Fast AI setup needed. Add a free Groq API key once, then send again.');
    }
    return await nativeChat(p);
  };

  if(oldTyping){
    window.typing=function(on,label){
      if(on)label='Luma is thinking…';
      return oldTyping(on,label);
    };
  }

  function injectStyle(){
    if(document.getElementById('luma30style'))return;
    const style=document.createElement('style');style.id='luma30style';style.textContent=`
      header{flex-wrap:nowrap!important}.brand{min-width:110px}.nativeQuickBar{z-index:4;display:flex;gap:8px;padding:9px 13px 4px;overflow-x:auto;scrollbar-width:none;background:rgba(251,250,255,.72);backdrop-filter:blur(18px)}.nativeQuickBar::-webkit-scrollbar{display:none}.nativeQuickBar .screenbtn{flex:0 0 auto;height:40px}.aiSetupBackdrop{position:fixed;z-index:80;inset:0;background:rgba(24,18,50,.42);backdrop-filter:blur(7px);display:flex;align-items:flex-end}.aiSetupCard{width:100%;background:#fbfaff;border-radius:28px 28px 0 0;padding:18px 18px max(24px,env(safe-area-inset-bottom));box-shadow:0 -18px 70px rgba(43,29,99,.25)}.aiSetupCard h2{margin:0 0 6px;font-size:22px}.aiSetupCard p{margin:0 0 13px;color:#716e82;font-size:13px;line-height:1.45}.aiKeyInput{width:100%;border:1px solid rgba(90,70,170,.18);border-radius:16px;background:white;padding:13px 14px;font-size:14px;outline:none}.aiSetupActions{display:flex;gap:8px;margin-top:10px;flex-wrap:wrap}.aiSetupActions button{border:0;border-radius:14px;padding:11px 13px;font-weight:800;background:#eeeafd;color:#544d76}.aiSetupActions .primary{color:white;background:linear-gradient(135deg,#6b4cff,#b745ff)}.aiSetupMsg{font-size:12px;margin-top:9px;color:#8a4660;min-height:18px}`;document.head.appendChild(style);
  }

  function addHeaderTools(){
    injectStyle();
    if(document.getElementById('nativeQuickBar'))return;
    const h=document.querySelector('header');if(!h)return;
    const bar=document.createElement('div');bar.id='nativeQuickBar';bar.className='nativeQuickBar';
    h.insertAdjacentElement('afterend',bar);
    const voice=document.createElement('button');voice.id='voiceBtn';voice.className='screenbtn';voice.textContent='Voice';voice.onclick=function(){try{if(LumaNative.isVoiceActive())LumaNative.stopVoiceMode();else LumaNative.startVoiceMode();setTimeout(syncNative,700)}catch(e){alert('Voice error: '+e.message)}};bar.appendChild(voice);
    const control=document.createElement('button');control.id='controlBtn';control.className='screenbtn';control.textContent='Control';control.onclick=function(){try{if(LumaNative.isDeviceControlEnabled())alert('Device Control is already active. Just type a command like: open Brawl Stars then click Brawlers');else LumaNative.openDeviceControlSettings()}catch(e){alert('Unable to open Device Control settings.')}};bar.appendChild(control);
    const screen=document.getElementById('screenBtn');if(screen){screen.textContent='Live Screen';bar.appendChild(screen)}
    const setup=document.createElement('button');setup.id='aiSetupBtn';setup.className='screenbtn';setup.textContent='AI Setup';setup.onclick=openAiSetup;bar.appendChild(setup);
  }

  window.openAiSetup=function(){
    injectStyle();
    if(document.getElementById('aiSetupBackdrop'))return;
    const wrap=document.createElement('div');wrap.id='aiSetupBackdrop';wrap.className='aiSetupBackdrop';
    wrap.innerHTML=`<div class="aiSetupCard"><h2>Fast AI Setup</h2><p><b>Groq is recommended</b> for Luma: very fast GPT‑OSS 120B chat and Qwen vision for Live Screen. The free tier needs your own API key once. The key is saved privately in Luma on this phone and is not added to GitHub. OpenRouter keys are also accepted as a backup.</p><input id="aiKeyInput" class="aiKeyInput" type="password" autocomplete="off" placeholder="Paste gsk_… or sk-or-… key"><div class="aiSetupActions"><button class="primary" id="saveAiKeyBtn">Save & use</button><button id="groqKeyBtn">Get free Groq key</button><button id="orKeyBtn">OpenRouter key</button><button id="clearAiBtn">Clear key</button><button id="closeAiBtn">Close</button></div><div class="aiSetupMsg" id="aiSetupMsg"></div></div>`;
    document.body.appendChild(wrap);
    wrap.onclick=e=>{if(e.target===wrap)wrap.remove()};
    document.getElementById('saveAiKeyBtn').onclick=function(){
      const input=document.getElementById('aiKeyInput');const msg=document.getElementById('aiSetupMsg');const key=input.value.trim();
      if(!key){msg.textContent='Paste your API key first.';return}
      try{const name=LumaNative.saveAiKey(key);if(!name){msg.textContent='That key format is not recognized. Use a Groq gsk_ key or OpenRouter sk-or- key.';return}msg.textContent=name+' connected.';input.value='';setTimeout(()=>{wrap.remove();syncNative()},550)}catch(e){msg.textContent='Could not save key: '+e.message}
    };
    document.getElementById('groqKeyBtn').onclick=()=>{try{LumaNative.openGroqKeysPage()}catch(e){}};
    document.getElementById('orKeyBtn').onclick=()=>{try{LumaNative.openOpenRouterKeysPage()}catch(e){}};
    document.getElementById('clearAiBtn').onclick=()=>{try{LumaNative.clearAiKeys();document.getElementById('aiSetupMsg').textContent='Saved AI keys cleared.';syncNative()}catch(e){}};
    document.getElementById('closeAiBtn').onclick=()=>wrap.remove();
  };

  function syncNative(){
    try{
      const v=document.getElementById('voiceBtn');if(v){const on=LumaNative.isVoiceActive();v.classList.toggle('on',on);v.textContent=on?'Voice ON':'Voice'}
      const c=document.getElementById('controlBtn');if(c){const on=LumaNative.isDeviceControlEnabled();c.classList.toggle('on',on);c.textContent=on?'Control ON':'Control'}
      const s=document.getElementById('screenBtn');if(s){const on=LumaNative.isScreenSharing();s.classList.toggle('on',on);s.textContent=on?'Screen ON':'Live Screen'}
      const a=document.getElementById('aiSetupBtn');if(a){const on=LumaNative.hasAiKey();a.classList.toggle('on',on);a.textContent=on?'AI Connected':'AI Setup'}
      if(typeof busy==='undefined'||!busy)setStatus(provider(),false);
    }catch(e){}
  }

  window.onNativeVoiceStatus=function(){syncNative()};
  const originalScreenStatus=window.onNativeScreenShareStatus;
  window.onNativeScreenShareStatus=function(active,message){try{if(typeof originalScreenStatus==='function')originalScreenStatus(active,message)}catch(e){}syncNative()};

  try{window.checkHeartbeat=function(){}}catch(e){}
  addHeaderTools();
  const sub=document.querySelector('.sub');if(sub)sub.textContent='Fast Groq/OpenRouter chat, image/video generation, Live Screen OCR, background voice, and optional device control.';
  syncNative();setInterval(syncNative,1800);
})();