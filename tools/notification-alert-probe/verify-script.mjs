/** Exercises the PowerShell driver with a fake adb, never an Android device. */
import { mkdtemp, readFile, writeFile, copyFile, mkdir, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const base=dirname(fileURLToPath(import.meta.url));
const pwsh=process.argv[2];
if (!pwsh) throw new Error('Usage: node tools/notification-alert-probe/verify-script.mjs /absolute/path/to/pwsh');
const temp=await mkdtemp(resolve(tmpdir(),'ticbox-alert-probe-'));
const results=[];
try {
  await mkdir(resolve(temp,'path with spaces'));
  const adb=resolve(temp,'path with spaces','fake adb');
  await writeFile(adb, `#!/usr/bin/env node
import fs from 'node:fs';
const args=process.argv.slice(2); fs.appendFileSync(process.env.PROBE_TEST_CALLS,JSON.stringify(args)+'\\n');
if(args[0]!=='-s')process.exit(80);
const cmd=args.slice(2), mode=process.env.PROBE_TEST_MODE;
if(cmd[0]==='get-state')console.log('device');
else if(cmd[0]==='install')console.log('Success');
else if(cmd[0]==='shell'&&cmd[1]==='getprop')console.log(cmd[2]==='ro.build.version.sdk'?'35':'phone');
else if(cmd[0]==='shell'&&cmd[1]==='pm'&&cmd[2]==='grant'){}
else if(cmd[0]==='shell'&&cmd[1]==='am'){
 const name=cmd[cmd.indexOf('case')+1];
 if(cmd[2]!=='broadcast'||!cmd.includes('dev.ticbox.alertprobe/.ProbeReceiver'))process.exit(81);
 const config={SILENT:'0 vibrate=0',SOUND_ONLY:'1 vibrate=0',VIBRATE_ONLY:'0 vibrate=1',BOTH:'1 vibrate=1'};
 const data=name==='CHECK'?'ready=1 dnd='+(mode==='dnd'?'2':'1')+' ringer=2':name==='CLEANUP'?'cleanup=1':'case='+name+' sound='+config[name]+' importance=3 dnd=1 ringer=2';
 console.log('Broadcast completed: result=-1, data="ALERT_PROBE_OK '+data+'"');
}else if(cmd[0]==='shell'&&cmd[1]==='dumpsys'){
 if(cmd[3]==='provider')console.log('1 items in bridged items map (bridged from remote)\\n1 active stream items:');
 else console.log(mode==='disconnected'?'linkPhase=NEGOTIATING':'linkPhase=LINK_READY linkSessionId=1\\nnotificationPhase=ACTIVE notificationSessionId=1\\nenabled=true notificationAccessGranted=true listenerConnected=true\\ndataPhase=ACTIVE dataSessionId=1 matchingNotificationSession=true');
}else process.exit(82);
`,{mode:0o700});
  // The fake adb is a standalone ESM file even outside the repository's package scope.
  await writeFile(resolve(temp,'path with spaces','package.json'),' {"type":"module"}\n');
  const wrapper=resolve(temp,'run-test.ps1');
  await writeFile(wrapper, `param([string]$Driver,[string]$Adb,[string]$Report,[string]$Mode)
$ErrorActionPreference='Stop'
$tokens=$null; $parseErrors=$null
[System.Management.Automation.Language.Parser]::ParseFile($Driver,[ref]$tokens,[ref]$parseErrors)>$null
if($parseErrors.Count){throw 'Invalid PowerShell syntax'}
$global:ProbeAnswers=[System.Collections.Generic.Queue[string]]::new()
$answers=if($Mode -eq 'quit'){@('', '', 'q')}else{@('', '', 'y,n,n', '', 'y,n,y', '', 'y,y,n', '', 'y,y,y')}
foreach($answer in $answers){$global:ProbeAnswers.Enqueue($answer)}
function global:Read-Host {param([string]$Prompt) if($global:ProbeAnswers.Count -eq 0){throw 'Unexpected interactive prompt'};return $global:ProbeAnswers.Dequeue()}
function global:Start-Sleep {param([int]$Seconds)}
$parameters=@{PhoneSerial='test-phone';Adb=$Adb;ReportPath=$Report}
if($Mode -eq 'with-watch-counts'){$parameters.WatchSerial='test-watch'}
& $Driver @parameters
`);
  for (const mode of ['ok','with-watch-counts','dnd','disconnected','quit','bad-hash']) {
    const dir=resolve(temp,mode); await mkdir(dir);
    const driver=resolve(dir,'Test-NotificationAlerts.ps1');
    await copyFile(resolve(base,'Test-NotificationAlerts.ps1'),driver);
    const apk=Buffer.from('fake-APK-for-driver-test');
    await writeFile(resolve(dir,'ticbox-alert-probe.apk'),apk);
    await writeFile(resolve(dir,'probe-manifest.json'),JSON.stringify({apkSha256:mode==='bad-hash'?'invalid':createHash('sha256').update(apk).digest('hex')}));
    const calls=resolve(dir,'calls.jsonl'), reportPath=resolve(dir,'report.json');
    const run=spawnSync(pwsh,['-NoLogo','-NoProfile','-NonInteractive','-File',wrapper,'-Driver',driver,'-Adb',adb,'-Report',reportPath,'-Mode',mode],
      {encoding:'utf8',timeout:30_000,env:{...process.env,PROBE_TEST_MODE:mode,PROBE_TEST_CALLS:calls}});
    const invocations=(await readFile(calls,'utf8')).trim().split('\n').map(JSON.parse);
    const posts=invocations.filter(a=>['SILENT','SOUND_ONLY','VIBRATE_ONLY','BOTH'].includes(a[a.indexOf('case')+1]));
    if(mode==='ok'||mode==='with-watch-counts'||mode==='quit') {
      assert.equal(run.status,0,run.stdout+run.stderr);
      const report=JSON.parse((await readFile(reportPath,'utf8')).replace(/^\uFEFF/,''));
      assert.equal(report.cleanup,'test_notifications_and_channels_removed');
      assert.equal(report.watchAdbUsed,mode==='with-watch-counts');
      assert.equal(report.status,mode==='quit'?'stopped_by_user':'observations_recorded');
      assert.equal(report.cases.length,mode==='quit'?0:4);
      if(mode!=='quit') {
        assert.deepEqual(report.cases.map(c=>[c.observed.vibrated,c.observed.sounded]),[[false,false],[false,true],[true,false],[true,true]]);
        assert.deepEqual(posts.map(a=>a[a.indexOf('case')+1]),['SILENT','SOUND_ONLY','VIBRATE_ONLY','BOTH']);
        if(mode==='with-watch-counts')assert.ok(report.cases.every(c=>c.watchCounts.bridged===1&&c.watchCounts.active===1));
        else assert.ok(report.cases.every(c=>c.watchCounts===null));
      }
    } else {
      assert.notEqual(run.status,0,run.stdout+run.stderr);
      assert.equal(posts.length,0,`Unexpected test notification in ${mode}`);
      if(mode==='bad-hash')assert.ok(!invocations.some(a=>a.includes('install')));
      else {
        const report=JSON.parse((await readFile(reportPath,'utf8')).replace(/^\uFEFF/,''));
        assert.equal(report.status,'interrupted'); assert.equal(report.cleanup,'test_notifications_and_channels_removed');
      }
    }
    if(mode!=='with-watch-counts')assert.ok(invocations.every(a=>a[1]==='test-phone'),'Default/phone-only mode contacted another device');
    results.push({mode,passed:true});
  }
  console.log(JSON.stringify({schema:'ticbox-alert-probe-script-tests/v1',results,scope:'fake adb only; no phone/watch used'},null,2));
} finally { await rm(temp,{recursive:true,force:true}); }
