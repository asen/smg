
// These are defined in the plugin html content
//var gl_smgAction = { scala.xml.Unparsed("\"" + actionId + "\"") }
//var gl_smgObjectView = { scala.xml.Unparsed(Json.toJson(ov).toString) }
//var gl_smgFetchUrl = { scala.xml.Unparsed("\"" + furl + "\"") }

// These are populated from the fetchUrl response
var gl_smgHeader = null;
var gl_smgRows = null;

// Some simple helper functions
function jsgraph_unpack(rows, key) {
  return rows.map(function(row) { return row[key]; });
}

function wrapText(txt, max) {
  var ret = [];
  var cur = "";
  txt.split(" ").forEach(function(word){
     cur += word + " "
     if (cur.length > max) {
        ret.push(cur)
        cur = ""
     }
  });
  ret.push(cur)
  return ret.join("<br>\n");
}

function plotlyChart(data, subtitle) {
  var myTitle = wrapText(gl_smgObjectView.title, 120)
  var layout = {
       title: myTitle + " (" + subtitle + ")",
     };

  //console.log(data);
  var plContainer = document.getElementById('plContainer')
  Plotly.purge(plContainer);
  Plotly.plot(plContainer, data, layout);
}

//////////////////////////////////////////////////
/// Zoom chart

function zoomChartData(hdr, rows) {

  var plData = hdr.slice(1).map(function(hdr, ix){
    return {
      x: jsgraph_unpack(rows, 0).map(function(u){ return new Date(u) }),
      y: jsgraph_unpack(rows, ix+1),
      mode: 'lines+markers',
      name: hdr
    };
  });

  return plData;
}

/// Zoom chart
//////////////////////////////////////////////////

//////////////////////////////////////////////////
/// Histogram chart

var gl_DefaultHistogramVar = 0;

function histogramChartData(hdr, rows, ix) {

 var plData = [
   {
     x: jsgraph_unpack(rows, ix + 1),
     type: 'histogram',
     marker: {
        color: 'rgba(100,250,100,0.7)',
     },
   }
 ];

 return plData;
}

function histogramInputsChange(){
  var varIx = parseInt(document.getElementById("jsgVarInput").value);
  console.log(varIx)
  var plData = histogramChartData(gl_smgHeader, gl_smgRows, varIx);
  plotlyChart(plData);
}

function createVarSelectInput() {
    if (!document.getElementById("jsgVarInput")){
      var parent = document.getElementById("jsgraphDiv");
      var container = document.getElementById("plContainer");
      var myDiv = document.createElement("div")
      var mySelectVarHtml= '<label>Selected value: </label>' +
        '<select id="jsgVarInput" onchange="histogramInputsChange()" default="0" />' +
        gl_smgObjectView.vars.map(function(v,ix){
              var selected = "";
              if (ix == 0) selected = 'selected';
              return '<option ' + selected + ' value="'+ ix +'">' + v.label + '</option>'
            }).join("\n") +
        '</select>'

      myDiv.innerHTML = mySelectVarHtml;

      parent.insertBefore(myDiv, container);
    }
}

/// Histogram chart
//////////////////////////////////////////////////


//////////////////////////////////////////////////
/// Derive (a.k.a. 1st derivative) chart

function derive1(arr, deltaT) {
  if ((arr.length == 0) || (deltaT == 0)) {
    return []
  }
  var prev = arr[0];
  return arr.slice(1).map(function(cur){
    var ret = (cur - prev) / deltaT
    prev = cur
    return ret
  });
}

function derive1ChartData(hdr, rows, deltaT) {
  //console.log("using deltaT=" + deltaT);
  var plData = hdr.slice(1).map(function(hdr, ix){
    return {
      // the derive1 function reduces data points array length with 1 so skip one ts
      x: jsgraph_unpack(rows, 0).slice(1).map(function(u){ return new Date(u) }),
      y: derive1(jsgraph_unpack(rows, ix+1), deltaT),
      mode: 'lines+markers',
      name: hdr + " (dY / " + deltaT + ")"
    };
  });

  return plData;
}

/// Derive (a.k.a. 1st derivative) chart
//////////////////////////////////////////////////


//////////////////////////////////////////////////
/// Common

function displayChart(action, hdr, rows) {
  var plData = null;
  switch (action) {
    case "hist":
      createVarSelectInput();
      plData = histogramChartData(hdr, rows, gl_DefaultHistogramVar);
      plotlyChart(plData, "Histogram");
    break;
    case "zoom":
      plData = zoomChartData(hdr, rows);
      plotlyChart(plData, "Zoom");
    break;
    case "deriv1":
      // calculate deltaT based on the first two timestamps.
      // luckily with rrdtool we always get them normalized (same between time series values)
      var twoTss = rows.slice(0,2)
      var deltaT =  twoTss.length < 2 ? 1 : (twoTss[1][0] - twoTss[0][0]) / 1000
      plData = derive1ChartData(hdr, rows, deltaT);
      plotlyChart(plData, "1st derivative: dY / " + deltaT + " seconds");
    break;
    default: $('#plContainer').html("Invalid action") ;
  }
}

/// Entry point


$.get( gl_smgFetchUrl, function( data ) {
    $('#plContainer').html("")
    var lines = data.split("\n")
    gl_smgHeader = lines.splice(0,1)[0].split(",")
    //unixts,date,val1,val2
    gl_smgHeader.splice(1,1)
    gl_smgRows = lines.map(function(ln, ix, arr ){
        var r = ln.split(",")
        r.splice(1,1)
        r[0] = parseInt(r[0]) * 1000
        for (var i = 1, l = r.length ; i < l ; i++ ) {
            r[i] = parseFloat(r[i])
        }
        return r
    });

    displayChart(gl_smgAction, gl_smgHeader, gl_smgRows)
}).fail(function(err){
  $('#plContainer').html(err)
});

