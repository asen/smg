
var gl_svgDataCache = {}

function svgData(dataUrl, callback) {
    if (dataUrl in gl_svgDataCache) {
        callback(gl_svgDataCache[dataUrl])
    } else {
        $.ajax(dataUrl, {
            success: function(data) {
               gl_svgDataCache[dataUrl] = data
               callback(data)
            }
        })
    }
}

function showSvgData(dataUrl, elem) {
    svgData(dataUrl,
       function(data){
          var s = data.lst.map(function(elem){return elem["t"]}).join("\n")
          var ttl = elem.getElementsByTagName('title')[0]
          ttl.textContent = s
       }
    );
}

