package com.inspectionbrowser.app.browser

/**
 * "Desktop site", done the way Chrome does it. Changing the user agent alone isn't enough:
 * most sites pick their layout from the viewport width (CSS media queries, `innerWidth`), and
 * their `<meta name=viewport content="width=device-width">` keeps that at the phone's width.
 * So pages are laid out at a desktop width (WebView zooms them out to fit), and scripts that
 * ask `navigator.userAgentData` whether this is a phone are told it isn't.
 */
object DesktopMode {
    /** CSS pixels: wide enough for the usual desktop breakpoints (992 and 1024), as Chrome uses about 980. */
    const val WIDTH = 1024

    /** Runs at document start in the top frame of every page while desktop mode is on. */
    const val SCRIPT = """(function(){if(window.top!==window||window.__ibDesktop)return;window.__ibDesktop=1;
var want='width=$WIDTH';
function fix(m){if(m&&m.tagName==='META'&&String(m.getAttribute('name')).toLowerCase()==='viewport'&&m.getAttribute('content')!==want)m.setAttribute('content',want);}
function scan(){var ms=document.getElementsByTagName('meta');for(var i=0;i<ms.length;i++)fix(ms[i]);}
var mo=new MutationObserver(function(rs){for(var i=0;i<rs.length;i++){var r=rs[i];if(r.type==='attributes')fix(r.target);
for(var j=0;j<r.addedNodes.length;j++){var n=r.addedNodes[j];if(n.nodeType!==1)continue;fix(n);if(n.getElementsByTagName){var ms=n.getElementsByTagName('meta');for(var k=0;k<ms.length;k++)fix(ms[k]);}}}});
mo.observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['content','name']});
scan();
addEventListener('load',function(){scan();setTimeout(function(){mo.disconnect();},3000);});
try{var d=navigator.userAgentData;if(d){var brands=d.brands,platform='Linux';
var fake={brands:brands,mobile:false,platform:platform,toJSON:function(){return{brands:brands,mobile:false,platform:platform};},
getHighEntropyValues:function(h){return d.getHighEntropyValues(h).then(function(v){v.mobile=false;v.platform=platform;v.model='';return v;});}};
Object.defineProperty(Navigator.prototype,'userAgentData',{get:function(){return fake;},configurable:true});}}catch(e){}
})();"""
}
