(function(){
  const POLL_OPENAI='https://text.pollinations.ai/openai';
  const POLL_TEXT='https://text.pollinations.ai/';
  const oldTyping=typeof typing==='function'?typing:null;

  async function fetchTimed(url,options,ms){
    const controller=new AbortController();
    const timer=setTimeout(()=>controller.abort(),ms);
    try{return await fetch(url,{...options,signal:controller.signal})}
    finally{clearTimeout(timer)}
  }

  function buildMessages(prompt){
    const c=typeof active==='function'?active():null;
    let screen='';
    try{if(window.LumaNative&&LumaNative.isScreenSharing())screen=LumaNative.getScreenContext()||''}catch(e){}
    const system='You are Luma, a fast and capable mobile AI assistant. Answer directly and clearly. For difficult requests, reason carefully before answering.'+(screen?' The user explicitly enabled Live Screen. Current screen context: '+screen:'');
    let history=(c&&Array.isArray(c.messages)?c.messages:[])
      .filter(m=>m.kind==='text'&&(m.role==='user'||m.role==='assistant'))
      .slice(-14)
      .map(m=>({role:m.role==='assistant'?'assistant':'user',content:String(m.text||'')}));
    const p=String(prompt||'').trim();
    if(!history.length||history[history.length-1].role!=='user'||history[history.length-1].content.trim()!==p){
      history.push({role:'user',content:p});
    }
    return [{role:'system',content:system},...history];
  }

  function parseOpenAI(j){
    const out=j&&j.choices&&j.choices[0]&&j.choices[0].message&&j.choices[0].message.content;
    return out?String(out).trim():'';
  }

  async function pollinationsChat(prompt){
    const messages=buildMessages(prompt);
    const body={model:'openai-fast',messages,stream:false,temperature:0.65,max_tokens:1200};
    try{
      const r=await fetchTimed(POLL_OPENAI,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)},12000);
      if(!r.ok)throw new Error('HTTP '+r.status);
      const j=await r.json();
      const out=parseOpenAI(j);
      if(!out)throw new Error('empty response');
      return out;
    }catch(firstError){
      const r=await fetchTimed(POLL_TEXT,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({model:'openai-fast',messages,temperature:0.65,max_tokens:1200})},10000);
      if(!r.ok)throw new Error('AI HTTP '+r.status);
      const raw=(await r.text()).trim();
      if(!raw)throw new Error('empty response');
      try{
        const parsed=JSON.parse(raw);
        return parseOpenAI(parsed)||String(parsed.response||parsed.text||raw).trim();
      }catch(e){return raw}
    }
  }

  window.generateChat=async function(prompt){
    try{
      const p=String(prompt||'').trim();
      if(window.LumaNative&&/^(open|launch|click|tap|press|go back|back|home)\b/i.test(p)&&LumaNative.isDeviceControlEnabled()){
        const result=LumaNative.runDeviceTask(p);
        if(result)return result;
      }
    }catch(e){}
    try{
      return await pollinationsChat(prompt);
    }catch(e){
      const timedOut=e&&e.name==='AbortError';
      throw new Error(timedOut?'The AI took too long. Please tap send again.':'The fast AI is temporarily unavailable. Please retry in a moment.');
    }
  };

  if(oldTyping){
    window.typing=function(on,label){
      if(on)label='Luma is thinking…';
      return oldTyping(on,label);
    };
  }

  function nativeSafe(name){try{return!!(window.LumaNative&&LumaNative[name])}catch(e){return false}}
  function addHeaderButton(id,label,handler){
    if(document.getElementById(id))return;
    const h=document.querySelector('header');if(!h)return;
    const b=document.createElement('button');b.id=id;b.className='screenbtn';b.textContent=label;b.onclick=handler;
    const ref=document.getElementById('screenBtn');h.insertBefore(b,ref||h.lastChild);return b;
  }
  function syncNative(){
    try{
      const v=document.getElementById('voiceBtn');
      if(v&&nativeSafe('isVoiceActive')){const on=LumaNative.isVoiceActive();v.classList.toggle('on',on);v.textContent=on?'Voice on':'Voice'}
      const c=document.getElementById('controlBtn');
      if(c&&nativeSafe('isDeviceControlEnabled')){const on=LumaNative.isDeviceControlEnabled();c.classList.toggle('on',on);c.textContent=on?'Control on':'Control'}
    }catch(e){}
  }
  addHeaderButton('voiceBtn','Voice',function(){
    try{if(!window.LumaNative)return alert('Voice mode requires Android.');if(LumaNative.isVoiceActive())LumaNative.stopVoiceMode();else LumaNative.startVoiceMode();setTimeout(syncNative,900)}catch(e){alert('Voice mode error: '+e.message)}
  });
  addHeaderButton('controlBtn','Control',function(){
    try{if(!window.LumaNative)return alert('Device control requires Android.');LumaNative.openDeviceControlSettings()}catch(e){alert('Unable to open accessibility settings.')}
  });

  const welcomeBox=document.querySelector('.sub');
  if(welcomeBox)welcomeBox.textContent='Fast anonymous AI, image/video generation, Live Screen, background voice, and optional device control.';
  try{setStatus('openai-fast ready · no sign-in',false)}catch(e){}
  syncNative();setInterval(syncNative,2000);
})();