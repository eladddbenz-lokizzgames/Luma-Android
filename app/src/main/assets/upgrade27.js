(function(){
  const KEYLESS='https://keylessai.thryx.workers.dev/v1/chat/completions';
  const oldGenerateChat=typeof generateChat==='function'?generateChat:null;
  const oldTyping=typeof typing==='function'?typing:null;

  async function fastChat(prompt){
    const c=typeof active==='function'?active():null;
    let screen='';
    try{ if(window.LumaNative&&LumaNative.isScreenSharing()) screen=LumaNative.getScreenContext()||''; }catch(e){}
    const history=(c&&Array.isArray(c.messages)?c.messages:[]).filter(m=>m.kind==='text').slice(-16).map(m=>({role:m.role==='assistant'?'assistant':'user',content:m.text||''}));
    const system='You are Luma, a fast, capable mobile AI assistant. Be concise but useful. Think through multi-step requests before answering.'+(screen?' The user explicitly enabled Live Screen. Current screen context: '+screen:'');
    const body={model:'openai-fast',messages:[{role:'system',content:system},...history,{role:'user',content:prompt}],stream:false,temperature:0.65};
    const r=await fetch(KEYLESS,{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer not-needed'},body:JSON.stringify(body)});
    if(!r.ok) throw new Error('Fast AI HTTP '+r.status);
    const j=await r.json();
    const out=j&&j.choices&&j.choices[0]&&j.choices[0].message&&j.choices[0].message.content;
    if(!out) throw new Error('Fast AI returned no response');
    return String(out).trim();
  }

  window.generateChat=async function(prompt){
    try{
      const p=String(prompt||'').trim();
      if(window.LumaNative&&/^(open|launch|click|tap|press|go back|back|home)\b/i.test(p) && LumaNative.isDeviceControlEnabled()){
        const result=LumaNative.runDeviceTask(p);
        if(result) return result;
      }
    }catch(e){}
    try{return await fastChat(prompt)}catch(e){
      if(oldGenerateChat) return await oldGenerateChat(prompt);
      throw e;
    }
  };

  if(oldTyping){
    window.typing=function(on,label){
      if(label&&String(label).includes('AI Horde')) label='Thinking with Fast AI…';
      return oldTyping(on,label);
    };
  }

  function nativeSafe(name){
    try{return !!(window.LumaNative&&LumaNative[name])}catch(e){return false}
  }
  function addHeaderButton(id,label,handler){
    if(document.getElementById(id))return;
    const h=document.querySelector('header'); if(!h)return;
    const b=document.createElement('button'); b.id=id; b.className='screenbtn'; b.textContent=label; b.onclick=handler;
    const ref=document.getElementById('screenBtn'); h.insertBefore(b,ref||h.lastChild); return b;
  }
  function syncNative(){
    try{
      const v=document.getElementById('voiceBtn');
      if(v&&nativeSafe('isVoiceActive')){const on=LumaNative.isVoiceActive();v.classList.toggle('on',on);v.textContent=on?'Voice on':'Voice';}
      const c=document.getElementById('controlBtn');
      if(c&&nativeSafe('isDeviceControlEnabled')){const on=LumaNative.isDeviceControlEnabled();c.classList.toggle('on',on);c.textContent=on?'Control on':'Control';}
    }catch(e){}
  }
  addHeaderButton('voiceBtn','Voice',function(){
    try{if(!window.LumaNative)return alert('Voice mode requires Android.');if(LumaNative.isVoiceActive())LumaNative.stopVoiceMode();else LumaNative.startVoiceMode();setTimeout(syncNative,900)}catch(e){alert('Voice mode error: '+e.message)}
  });
  addHeaderButton('controlBtn','Control',function(){
    try{if(!window.LumaNative)return alert('Device control requires Android.');LumaNative.openDeviceControlSettings();}catch(e){alert('Unable to open accessibility settings.')}
  });

  const welcomeBox=document.querySelector('.sub');
  if(welcomeBox) welcomeBox.textContent='Fast no-sign-in AI, image/video generation, Live Screen, background voice, and optional device control.';
  try{setStatus('Fast AI ready · no sign-in',false)}catch(e){}
  syncNative(); setInterval(syncNative,2000);
})();