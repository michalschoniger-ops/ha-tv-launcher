#!/usr/bin/env node
// Isolated loopback-only design preview. No .env, HA, ADB, runtime or outgoing network.
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readFile } from 'node:fs/promises';
import { demoDashboard } from '../lib/demo.mjs';

const publicRoot=fileURLToPath(new URL('../public/',import.meta.url));
const port=Number(process.env.PREVIEW_PORT||4318);
if(!Number.isInteger(port)||port<1024||port>65535||port===4317)throw Error('Use a separate preview port.');
const json=(res,status,body)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(body));};
const apps=['com.netflix.ninja','net.cme.voyo.cz.tvapp','cz.prima.tvapp','com.google.android.youtube.tv','com.spotify.tv.android','org.videolan.vlc'].map(appId=>({appId}));
const plans=[{title:'Přepnout domácnost do režimu Noc'},{title:'Vypnout televizi'},{title:'Zamknout Vchodove dvere'},{title:'Vypnout všechna světla'},{title:'Zavřít zahradní vodu'}];
const tokens=new Map();
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml','.geojson':'application/geo+json','.png':'image/png'};
function dashboard(){
 const now=new Date(),data=demoDashboard(now),date=new Intl.DateTimeFormat('sv-SE',{timeZone:'Europe/Prague'}).format(now);
 data.household={
  security:['Domov','Garáž','Kůlna'].map((name,i)=>({id:['home','garage','shed'][i],name,state:i?'armed':'disarmed',available:true,coverageComplete:i>0,contactCount:i?2:8,availableContactCount:i?2:7,openContactCount:0,contacts:[{name:'Ukázkový kontakt',state:'closed',available:true}]})),
  // Public Prague/London coordinates are fictional, never copied from the household.
  people:[{id:'person_a',name:'Osoba A · ukázka',available:true,state:'home',latitude:50.0755,longitude:14.4378,locationAvailable:true,lastUpdated:now.toISOString()},{id:'person_b',name:'Osoba B · ukázka',available:true,state:'not_home',latitude:51.5072,longitude:-.1276,locationAvailable:true,lastUpdated:now.toISOString()}],
  zones:[{id:'demo',name:'Ukázková zóna · Praha',latitude:50.0755,longitude:14.4378,radius:100}],
  nameday:{available:true,names:['Ukázkové jméno'],date},
  birthdayCalendar:{available:true,localDate:date,timeZone:'Europe/Prague'},
  birthdaysToday:[{id:'demo-birthday',summary:'Ukázkové narozeniny',allDay:true}],
 };
 return data;
}
const server=http.createServer(async(req,res)=>{
 try{
  const url=new URL(req.url,'http://127.0.0.1:'+port),route=url.pathname;
  if(req.method==='GET'&&route==='/api/bootstrap')return json(res,200,{mode:'demo',authenticated:true,apps:[],assistant:{available:false,tvControl:true},photos:{},preview:true});
  if(req.method==='GET'&&route==='/api/dashboard')return json(res,200,dashboard());
  if(req.method==='GET'&&route==='/api/tv/apps')return json(res,200,{apps});
  if(req.method==='GET'&&route==='/api/photos')return json(res,200,[]);
  if(req.method==='GET'&&route==='/api/notifications')return json(res,200,{connected:true,houseConnected:true,notifications:[{id:'demo',title:'Ukázkové upozornění',message:'Toto je oddělený náhled. Televize ani HA se neovládají.'}]});
  if(req.method==='POST'&&route==='/api/night/prepare'){
   const confirmationId=crypto.randomUUID().replaceAll('-','');
   tokens.set(confirmationId,Date.now()+20000);
   for(const [id,expires]of tokens)if(expires<Date.now())tokens.delete(id);
   return json(res,200,{confirmationId,expiresAt:new Date(Date.now()+20000).toISOString(),routineName:'UKÁZKA · Dobrou noc',requiresTripleClick:true,plan:plans,warnings:['Oddělený náhled na PC. Žádný krok neovládá skutečné zařízení.'],preview:true});
  }
  if(req.method==='POST'&&route==='/api/night/commit'){
   let body='';for await(const chunk of req){body+=chunk;if(body.length>1024)throw Error('Body too large');}
   const {confirmationId}=JSON.parse(body||'{}'),expires=tokens.get(confirmationId);tokens.delete(confirmationId);
   return json(res,expires>Date.now()?200:409,{accepted:expires>Date.now(),verified:false,preview:true,error:'Ukázkové potvrzení vypršelo.'});
  }
  if(route.startsWith('/api/'))return json(res,409,{error:'Tato akce v odděleném náhledu neovládá žádné zařízení.'});
  if(req.method!=='GET')return json(res,405,{error:'Read only preview.'});
  const filename=path.resolve(publicRoot,'.'+decodeURIComponent(route==='/'?'/index.html':route));
  if(!filename.startsWith(publicRoot)||!mime[path.extname(filename)])return json(res,404,{error:'Not found'});
  let bytes=await readFile(filename);
  if(filename.endsWith('/index.html'))bytes=Buffer.from(bytes.toString().replace('<title>HA TV Launcher</title>','<title>HA TV Launcher · oddělený náhled</title>'));
  res.writeHead(200,{'Content-Type':mime[path.extname(filename)],'Cache-Control':'no-store','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'"});res.end(bytes);
 }catch{json(res,404,{error:'Preview resource not available.'});}
});
server.listen(port,'127.0.0.1',()=>console.log(`Isolated preview http://127.0.0.1:${port} — fictional data, no device control.`));
