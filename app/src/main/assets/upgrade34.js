(function(){
  function nativeOk(){try{return !!window.LumaNative}catch(e){return false}}
  function addStyle(){
    if(document.getElementById('luma34style'))return;
    const s=document.createElement('style');
    s.id='luma34style';
    s.textContent=`
      .agentBack{position:fixed;z-index:130;inset:0;background:rgba(18,14,38,.52);backdrop-filter:blur(10px);display:flex;align-items:flex-end}
      .agentSheet{width:100%;max-height:88vh;overflow:auto;background:#fbfaff;border-radius:30px 30px 0 0;padding:20px 18px max(28px,env(safe-area-inset-bottom));box-shadow:0 -20px 70px rgba(35,25,82,.3)}
      .agentSheet h2{margin:0 0 6px;color:#211c31;font-size:24px}.agentSheet p{margin:0 0 12px;color:#716b7f;line-height:1.45;font-size:13px}
      .agentStat{display:flex;justify-content:space-between;gap:8px;padding:11px 13px;background:#f1effa;border-radius:15px;color:#50496f;font-weight:800;font-size:13px;margin-bottom:10px}
      .agentInput{display:flex;gap:8px;margin:10px 0}.agentInput input{flex:1;border:1px solid #e1dcef;border-radius:14px;padding:12px;background:white;font-size:14px}.agentInput button,.agentBtns button{border:0;border-radius:14px;padding:11px 13px;font-weight:850;background:#eeeafd;color:#51496e}.agentInput button{background:linear-gradient(135deg,#6b4cff,#bd45ff);color:white}
      .agentBtns{display:grid;grid-template-columns:1fr 1fr 1fr;gap:7px;margin:10px 0}.agentBtns .danger{background:#ffe8ee;color:#b72750}.agentToggle{display:flex;align-items:center;justify-content:space-between;padding:11px 13px;border-radius:14px;background:white;border:1px solid #eee9f7;margin:8px 0;color:#3d3850;font-weight:750}
      .featureGrid{display:grid;grid-template-columns:1fr 1fr;gap:7px;margin-top:12px}.feature{background:white;border:1px solid #eee9f7;border-radius:14px;padding:10px;color:#504a61;font-size:12px;line-height:1.35}.feature b{display:block;color:#282239;margin-bottom:3px}.agentNote{font-size:11px!important;color:#8f899a!important;margin-top:10px!important}
    `;
    document.head.appendChild(s);
  }
  function addHeaderButton(){
    if(document.getElementById('agentBtn'))return;
    const h=document.querySelector('header'); if(!h)return;
    const b=document.createElement('button'); b.id='agentBtn'; b.className='screenbtn'; b.textContent='Agent'; b.onclick=openAgentPanel;
    const ref=document.getElementById('controlBtn');
    if(ref&&ref.parentNode===h) h.insertBefore(b,ref.nextSibling); else h.appendChild(b);
  }
  function status(){try{return nativeOk()?LumaNative.getAgentStatus():'Android only'}catch(e){return 'Unavailable'}}
  function fps(){try{return nativeOk()?LumaNative.getScreenTrackerFps():0}catch(e){return 0}}
  function suggest(){try{return nativeOk()&&LumaNative.isSuggestOnly()}catch(e){return false}}
  function refresh(){
    const st=document.getElementById('agentStatusText'); if(st)st.textContent=status();
    const fp=document.getElementById('agentFpsText'); if(fp)fp.textContent=(fps()||0)+' FPS raw';
    const sg=document.getElementById('agentSuggestToggle'); if(sg)sg.checked=suggest();
  }
  window.openLumaAgentPanel=openAgentPanel;
  function openAgentPanel(){
    addStyle();
    const old=document.getElementById('agentBack'); if(old)old.remove();
    const w=document.createElement('div'); w.id='agentBack'; w.className='agentBack';
    w.innerHTML=`<div class="agentSheet">
      <h2>Luma Continuous Agent</h2>
      <p>Persistent device automation for workflows, accessibility and testing. Luma can keep watching for screen elements and act until you pause or stop it. Competitive online-match autoplay is not enabled.</p>
      <div class="agentStat"><span id="agentStatusText"></span><span id="agentFpsText"></span></div>
      <div class="agentInput"><input id="agentCommand" placeholder="Example: wait for Continue then click Continue"><button id="agentRun">Run</button></div>
      <div class="agentBtns"><button id="agentPause">Pause</button><button id="agentResume">Resume</button><button id="agentStop" class="danger">STOP</button></div>
      <label class="agentToggle"><span>Suggest-only mode</span><input id="agentSuggestToggle" type="checkbox"></label>
      <div class="featureGrid">
        <div class="feature"><b>1. Persistent watch</b>Watch for a target until stopped.</div>
        <div class="feature"><b>2. 60-FPS cursor</b>Smooth visible cursor animation at ~16 ms steps.</div>
        <div class="feature"><b>3. Fast raw tracking</b>Consumes fresh screen frames up to display refresh rate.</div>
        <div class="feature"><b>4. 10-Hz OCR</b>Reads changing text locally up to 10 times/sec.</div>
        <div class="feature"><b>5. Smart target matching</b>Exact, partial and multi-word matching.</div>
        <div class="feature"><b>6. Wait conditions</b>Wait for text before the next action.</div>
        <div class="feature"><b>7. Retry recovery</b>Retries targets that appear late.</div>
        <div class="feature"><b>8. Swipe</b>Up, down, left and right gestures.</div>
        <div class="feature"><b>9. Scroll</b>Natural screen scrolling commands.</div>
        <div class="feature"><b>10. Long press</b>Hold accessible controls.</div>
        <div class="feature"><b>11. Double tap</b>Two-tap gesture support.</div>
        <div class="feature"><b>12. Type text</b>Enter text in the focused field.</div>
        <div class="feature"><b>13. Pause / Resume</b>Freeze automation without losing the task.</div>
        <div class="feature"><b>14. Action history</b>Stores a local activity log.</div>
        <div class="feature"><b>15. Suggest-only</b>Locate actions without executing them.</div>
      </div>
      <p class="agentNote">Raw tracking can approach 60 FPS on a 60-Hz screen, but semantic OCR remains capped around 10 Hz so it does not overload the phone. Cloud vision is intentionally slower and used only when needed.</p>
      <div class="agentBtns"><button id="agentClose" style="grid-column:1/-1">Close</button></div>
    </div>`;
    document.body.appendChild(w);
    w.onclick=e=>{if(e.target===w)w.remove()};
    document.getElementById('agentRun').onclick=()=>{
      const q=document.getElementById('agentCommand').value.trim(); if(!q)return;
      try{const r=LumaNative.runOrEnableDeviceTask(q); const st=document.getElementById('agentStatusText'); if(st)st.textContent=r;}catch(e){}
      setTimeout(refresh,350);
    };
    document.getElementById('agentPause').onclick=()=>{try{LumaNative.pauseAgent()}catch(e){} setTimeout(refresh,120)};
    document.getElementById('agentResume').onclick=()=>{try{LumaNative.resumeAgent()}catch(e){} setTimeout(refresh,120)};
    document.getElementById('agentStop').onclick=()=>{try{LumaNative.stopAgent()}catch(e){} setTimeout(refresh,120)};
    document.getElementById('agentSuggestToggle').onchange=e=>{try{LumaNative.setSuggestOnly(!!e.target.checked)}catch(x){} setTimeout(refresh,120)};
    document.getElementById('agentClose').onclick=()=>w.remove();
    refresh();
  }
  addHeaderButton();
  setTimeout(addHeaderButton,600);
  setTimeout(addHeaderButton,1600);
})();
