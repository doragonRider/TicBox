#!/usr/bin/env node
/** Check maintained Markdown links, line coordinates and heading anchors offline. */
import { readdir, readFile, stat, mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve, relative, sep, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
async function markdownFiles(directory) {
  const entries=await readdir(resolve(root,directory),{withFileTypes:true});
  const files=await Promise.all(entries.map(entry=>entry.isDirectory()
    ?markdownFiles(directory+'/'+entry.name)
    :entry.isFile()&&entry.name.endsWith('.md')?[directory+'/'+entry.name]:[]));
  return files.flat();
}
function headingAnchors(content) {
  const anchors=new Set();
  let fence=null;
  for(const line of content.split('\n')) {
    const marker=line.match(/^\s*(`{3,}|~{3,})/);
    if(marker){if(!fence)fence=marker[1];else if(marker[1][0]===fence[0]&&marker[1].length>=fence.length)fence=null;continue;}
    if(fence)continue;
    const heading=line.match(/^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$/);
    if(!heading)continue;
    // GFM-style slugs for this project's plain-text/inline-code headings.
    const base=heading[1].toLowerCase().replace(/<[^>]+>/g,'').replace(/`/g,'')
      .replace(/[^\p{L}\p{M}\p{N}_\-\s]/gu,'').replace(/\s/g,'-');
    let slug=base, index=0;
    while(anchors.has(slug))slug=base+'-'+(++index);
    anchors.add(slug);
  }
  return anchors;
}
async function main() {
  if(process.argv.length!==2)throw new Error('No arguments supported');
  const reports=[
    'README.md',
    'README.zh-CN.md',
    'android/README.md',
    'android/thirdparty/kadb/README.md',
    'android/thirdparty/scrcpy-server/README.md',
    'android/ui/design-assets/material-symbols/README.md',
    'android/watch/healthmodule/SDK_PROVENANCE.md',
    'android/watch/toolboxagent/src/main/cpp/README.md',
    'tools/notification-alert-probe/README.md',
    'tools/screen-trace/README.md',
    ...(await markdownFiles('docs')),
    ...(await markdownFiles('assets')),
  ].sort();
  const checked=[], broken=[], reportFacts=[];
  let checkedHeadingAnchors=0;
  for(const report of reports) {
    const content=await readFile(resolve(root,report),'utf8');
    reportFacts.push({path:report,bytes:Buffer.byteLength(content),sha256:createHash('sha256').update(content).digest('hex')});
    for(const match of content.matchAll(/\]\(([^)]+)\)/g)) {
      const target=match[1].replace(/^<|>$/g,'');
      if(/^(?:https?:|mailto:)/.test(target))continue;
      const fragment=target.includes('#')?decodeURIComponent(target.slice(target.indexOf('#')+1)):null;
      let local=target.split('#')[0];
      const coordinate=local.match(/^(.*):(\d+)(?:[-–]\d+)?$/);
      const fragmentCoordinate=target.match(/#L(\d+)(?:-L\d+)?$/);
      if(coordinate)local=coordinate[1];
      const path=local?resolve(dirname(resolve(root,report)),decodeURIComponent(local)):resolve(root,report);
      const ref={report,target,path:relative(root,path).split(sep).join('/')};
      try {
        if(isAbsolute(ref.path)||ref.path==='..'||ref.path.startsWith('../'))throw new Error('outside workspace');
        if(/^(?:artifacts|results)\//.test(ref.path))throw new Error('research-only input; link the standalone specification instead');
        if(/(?:^|\/)(?:build|\.gradle|\.kotlin)(?:\/|$)/.test(ref.path))throw new Error('generated local output; describe its path as text instead of a document link');
        const info=await stat(path);
        if(coordinate || fragmentCoordinate) {
          if(!info.isFile())throw new Error('line coordinate on directory');
          const lines=(await readFile(path,'utf8')).split('\n').length;
          const line=Number(coordinate?coordinate[2]:fragmentCoordinate[1]);
          if(line<1 || line>lines)throw new Error(`line beyond file (${lines})`);
        } else if(fragment&&path.endsWith('.md')) {
          if(!headingAnchors(await readFile(path,'utf8')).has(fragment))throw new Error('heading anchor not found');
          checkedHeadingAnchors++;
        }
        checked.push(ref);
      } catch(error) {broken.push({...ref,reason:error.message});}
    }
  }
  await mkdir(resolve(root,'build/reports/verification'),{recursive:true});
  await writeFile(resolve(root,'build/reports/verification/docs.json'),JSON.stringify({schema:'ticbox-local-document-links/v2',reports:reportFacts,checked:checked.length,checkedHeadingAnchors,broken,limitations:['Local targets, optional line bounds and project Markdown heading anchors only; source meaning, web availability and runtime behavior are separate checks.','Original research and generated build directories are not document inputs; links into research directories are rejected.']},null,2)+'\n');
  if(broken.length)throw new Error(JSON.stringify(broken));
  process.stdout.write(`Verified ${checked.length} local links/line coordinates (${checkedHeadingAnchors} heading anchors) in ${reports.length} Markdown reports.\n`);
}
main().catch(error=>{process.stderr.write(error.message+'\n');process.exitCode=1;});
