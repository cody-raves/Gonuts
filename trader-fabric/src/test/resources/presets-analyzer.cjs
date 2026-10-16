const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync(process.argv[2],'utf8');
const source=html.slice(html.indexOf('async function openSavedPresets()'),html.indexOf('async function openSettings()'));
const nodes=[];function el(tag,cls,text=''){const n={tag,className:cls,textContent:text,children:[],dataset:{},style:{},appendChild(c){this.children.push(c);},replaceChildren(...c){this.children=c;},setAttribute(){},addEventListener(t,f){this[t]=f;},select(){}};Object.defineProperty(n,'innerHTML',{set(){throw Error('User labels must remain text');}});nodes.push(n);return n;}
const own=el('main'),changes=[];
const presets={ok:true,presets:[{id:'a',name:'<img src=x onerror=alert(1)>',code:'DB1-test',savedAt:1000}]};
const report={baseline:['24h wall-clock average'],findings:[{title:'Weak market',evidence:'10 losing sales',suggestion:'Trial one replacement',estimate:'profit/hour unmeasured'}],numbers:{'buy.scale_with_bank':1},lists:{},proposedCode:'DB1-proposal'};
const context=vm.createContext({own,el,view:'analyzer',Date,JSON,Object,confirm:()=>true,prompt:()=> 'Trial',navigator:{clipboard:{writeText:async()=>{}}},setTimeout:()=>{},toast:()=>{},failed:()=>{throw Error('Render failed');},button:(cls,text,click)=>Object.assign(el('button',cls,text),{click}),api:async name=>name==='savedpresets'?presets:{ok:true,note:'Ready',report},change:async(name,form)=>{changes.push({name,form});return {ok:true};},openSettings:()=>{}});
vm.runInContext(source,context);
(async()=>{await context.openSavedPresets();assert(nodes.some(n=>n.textContent===presets.presets[0].name));
 const rename=nodes.find(n=>n.textContent==='Rename');await rename.click();assert.equal(changes.at(-1).form.action,'rename');assert.equal(changes.at(-1).form.id,'a');
 await context.openAnalyzer();assert(nodes.some(n=>n.textContent==='Evidence: 10 losing sales'));assert(nodes.some(n=>n.textContent.includes('profit/hour unmeasured')));
 const save=nodes.find(n=>n.textContent==='Save suggested preset');await save.click();assert.equal(changes.at(-1).form.source,'proposal');assert.equal(changes.at(-1).form.action,'save');assert(!changes.some(c=>c.form.action==='apply'),'Saving a proposal must not apply it');
})().catch(e=>{console.error(e);process.exitCode=1;});
