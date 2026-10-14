// Observational review instrumentation. No account/credential text is retained.
const fs = require('node:fs');
const path = require('node:path');
function reporter(root) {
  const file = path.join(root, 'frontend-review.json');
  const evidence = { complete: false, snapshots: [], observations: {} };
  const save = () => fs.writeFileSync(file, JSON.stringify(evidence, null, 2));
  save();
  async function record(page, label) {
    // Measure settled states rather than the sidebar's 150ms colour transition.
    await page.waitForTimeout(180);
    const data = await page.evaluate(() => {
      const visible = el => { const r=el.getBoundingClientRect(); const s=getComputedStyle(el); return r.width>0 && r.height>0 && s.visibility==='visible' && s.display!=='none'; };
      const simple = el => el.tagName.toLowerCase()+(el.id ? '#'+el.id : [...el.classList].slice(0,2).map(c=>'.'+c).join(''));
      const selector = el => !el.id && !el.classList.length && el.parentElement?.classList.length ? simple(el.parentElement)+' > '+simple(el) : simple(el);
      const rgb = value => { const values=value.match(/[\d.]+/g)?.map(Number); return values && values.length>=3 && (values.length<4 || values[3]===1) ? values.slice(0,3) : null; };
      const luminance = values => values.map(v=>v/255).map(v=>v<=.04045 ? v/12.92 : ((v+.055)/1.055)**2.4).reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0);
      const modal=document.querySelector('dialog[open]'); const failures=[];
      for (const el of document.querySelectorAll('body *')) {
        if (!visible(el) || modal && !modal.contains(el) || ![...el.childNodes].some(n=>n.nodeType===3 && n.textContent.trim()) || el.closest('button:disabled, fieldset:disabled')) continue;
        let ancestor=el, background=null, excluded=false;
        while (ancestor) { const style=getComputedStyle(ancestor); if (Number(style.opacity)<1 || style.backgroundImage!=='none') { excluded=true; break; } if (!background) background=rgb(style.backgroundColor); ancestor=ancestor.parentElement; }
        const style=getComputedStyle(el); const foreground=rgb(style.color);
        if (excluded || !background || !foreground) continue;
        const a=luminance(foreground), b=luminance(background), ratio=(Math.max(a,b)+.05)/(Math.min(a,b)+.05);
        const size=parseFloat(style.fontSize); const minimum=size>=24 || size>=18.6667 && Number(style.fontWeight)>=700 ? 3 : 4.5;
        if (ratio<minimum) failures.push({selector:selector(el),ratio,minimum,fontSize:size,foreground,background});
      }
      return { pathname:location.pathname,title:document.title,width:innerWidth,rootFontSize:getComputedStyle(document.documentElement).fontSize,
        pageOverflow:document.documentElement.scrollWidth>innerWidth,
        activeTag:document.activeElement?.tagName,activeId:document.activeElement?.id,
        generationPrimaryFocused:document.activeElement===document.querySelector('dialog[aria-labelledby="generation-confirm-title"][open] button'),
        contrastCandidates:failures,
        overflowingRegions:[...document.querySelectorAll('[role="region"]')].filter(el=>visible(el)&&el.scrollWidth>el.clientWidth+1).map(el=>({selector:selector(el),clientWidth:el.clientWidth,scrollWidth:el.scrollWidth})),
        smallFormFonts:[...document.querySelectorAll('input,select,textarea')].filter(el=>visible(el)&&parseFloat(getComputedStyle(el).fontSize)<16).map(el=>({selector:selector(el),fontSize:getComputedStyle(el).fontSize})) };
    });
    evidence.snapshots.push({label,...data}); save();
  }
  async function instrument(browser) {
    const original = browser.newContext.bind(browser);
    browser.newContext = async (...args) => {
      const context = await original(...args);
      context.on('page', page => {
        const screenshot = page.screenshot.bind(page);
        page.screenshot = async options => {
          if (options?.path) await record(page,path.basename(options.path));
          return screenshot({...options,mask:[...(options?.mask||[]),page.locator('input[type="password"], #temporary-driver-password')]});
        };
      });
      return context;
    };
  }
  return {record,instrument,observe:(key,value)=>{evidence.observations[key]=value;save();},complete:()=>{evidence.complete=true;save();}};
}
module.exports={reporter};
