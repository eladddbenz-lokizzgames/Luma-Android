(function(){
  function nativeOk(){try{return !!window.LumaNative}catch(e){return false}}
  function controlOn(){try{return nativeOk()&&LumaNative.isDeviceControlEnabled()}catch(e){return false}}

  function addStyle(){
    if(document.getElementById('luma32style'))return;
    const s=document.createElement('style');
    s.id='luma32style';
    s.textContent=`
      .controlGuideBack{position:fixed;z-index:120;inset:0;background:rgba(21,17,42,.48);backdrop-filter:blur(8px);display:flex;align-items:flex-end}
      .controlGuide{width:100%;max-height:86vh;overflow:auto;background:#fbfaff;border-radius:30px 30px 0 0;padding:20px 18px max(28px,env(safe-area-inset-bottom));box-shadow:0 -20px 70px rgba(35,25,82,.28);direction:rtl;text-align:right}
      .controlGuide h2{margin:0 0 8px;font-size:24px;color:#211c31}.controlGuide p{margin:0 0 12px;color:#6c6879;line-height:1.55;font-size:14px}
      .controlState{display:flex;align-items:center;gap:9px;padding:12px 13px;border-radius:16px;background:#f1effa;margin:10px 0 14px;font-weight:800;color:#50496f}
      .controlState.on{background:#e6faf2;color:#187a56}.controlDot{width:10px;height:10px;border-radius:99px;background:#aaa}.controlState.on .controlDot{background:#20c985}
      .controlStep{padding:13px;border-radius:17px;background:white;border:1px solid rgba(90,70,170,.12);margin:9px 0}.controlStep b{display:block;margin-bottom:4px;color:#2c2740}.controlStep span{font-size:13px;color:#777286;line-height:1.5}
      .controlActions{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:12px}.controlActions button{border:0;border-radius:15px;padding:12px 9px;font-weight:850;background:#eeeafd;color:#51496e}.controlActions .primary{background:linear-gradient(135deg,#6b4cff,#b745ff);color:#fff}.controlActions .wide{grid-column:1/-1}.controlNote{margin-top:10px;font-size:12px;color:#8a8497;line-height:1.45}
    `;
    document.head.appendChild(s);
  }

  function stateText(){return controlOn()?'שליטה במכשיר פעילה':'שליטה במכשיר עדיין לא פעילה'}
  function refreshGuide(){
    const box=document.getElementById('controlStateBox');
    if(box){const on=controlOn();box.classList.toggle('on',on);const t=box.querySelector('.controlStateText');if(t)t.textContent=stateText()}
    const btn=document.getElementById('controlBtn');
    if(btn){const on=controlOn();btn.classList.toggle('on',on);btn.textContent=on?'Control ON':'Control'}
  }

  window.openDeviceControlWizard=function(){
    addStyle();
    const old=document.getElementById('controlGuideBack');if(old)old.remove();
    const wrap=document.createElement('div');wrap.id='controlGuideBack';wrap.className='controlGuideBack';
    wrap.innerHTML=`<div class="controlGuide">
      <h2>הפעלת שליטה במכשיר</h2>
      <p>Android לא מאפשר לאפליקציה להעניק לעצמה הרשאת נגישות. צריך לאשר אותה ידנית פעם אחת. אחרי שהפעלת אותה, Luma תזהה את ההרשאה ותשתמש בה אוטומטית בכל פקודת שליטה.</p>
      <div id="controlStateBox" class="controlState"><span class="controlDot"></span><span class="controlStateText"></span></div>
      <div class="controlStep"><b>שלב 1 — אפשר הגדרות מוגבלות</b><span>פתח את פרטי האפליקציה של Luma. לחץ על ⋮ למעלה ובחר <b>„אפשר הגדרות מוגבלות”</b> / <b>Allow restricted settings</b>. אשר קוד/טביעת אצבע אם Android מבקש.</span></div>
      <div class="controlStep"><b>שלב 2 — הפעל את Luma בנגישות</b><span>חזור ל־Luma, לחץ על „פתח נגישות”, בחר Luma והפעל את המתג. אם Luma עדיין אפורה, שלב 1 לא הושלם.</span></div>
      <div class="controlStep"><b>שלב 3 — זהו</b><span>אחרי שהמתג פעיל, אינך צריך לאשר שוב בכל פקודה. אפשר לכתוב למשל: „פתח Brawl Stars ואז לחץ על Brawlers”.</span></div>
      <div class="controlActions">
        <button id="openInfoBtn">1. פתח פרטי Luma</button>
        <button id="openAccessBtn" class="primary">2. פתח נגישות</button>
        <button id="checkControlBtn" class="wide">בדוק אם ההרשאה פעילה</button>
        <button id="closeControlBtn" class="wide">סגור</button>
      </div>
      <div class="controlNote">גם כאשר השליטה פעילה, Android מציג את Luma ברשימת שירותי הנגישות כדי שתוכל לכבות אותה בכל רגע.</div>
    </div>`;
    document.body.appendChild(wrap);
    refreshGuide();
    wrap.onclick=e=>{if(e.target===wrap)wrap.remove()};
    document.getElementById('openInfoBtn').onclick=()=>{try{LumaNative.openLumaAppInfo()}catch(e){alert('לא ניתן לפתוח את פרטי האפליקציה.')}};
    document.getElementById('openAccessBtn').onclick=()=>{try{LumaNative.openDeviceControlSettings()}catch(e){alert('לא ניתן לפתוח את הגדרות הנגישות.')}};
    document.getElementById('checkControlBtn').onclick=()=>{refreshGuide();if(controlOn())setTimeout(()=>{const w=document.getElementById('controlGuideBack');if(w)w.remove()},700)};
    document.getElementById('closeControlBtn').onclick=()=>wrap.remove();
  };

  function hookControl(){
    const btn=document.getElementById('controlBtn');
    if(!btn)return;
    btn.onclick=function(){
      if(controlOn()){
        openDeviceControlWizard();
      }else{
        openDeviceControlWizard();
      }
    };
    refreshGuide();
  }

  window.onNativeControlStateChanged=function(){refreshGuide()};
  setTimeout(hookControl,250);
  setTimeout(hookControl,1200);
  setInterval(refreshGuide,1800);
})();
