
// These are defined in the plugin html content
//var gl_smgAction = { scala.xml.Unparsed("\"" + actionId + "\"") }
//var gl_smgObjectView = { scala.xml.Unparsed(Json.toJson(ov).toString) }
//var gl_smgFetchUrl = { scala.xml.Unparsed("\"" + furl + "\"") }

// These are populated from the fetchUrl response
var gl_smgHeader = null;
var gl_smgRows = null;

function unpack(rows, key) {
    return rows.map(function(row) { return row[key]; });
}

function plotlyChart(data) {
  var layout = {
       title: gl_smgObjectView.title,
     };

  console.log(data);
  var plContainer = document.getElementById('plContainer')
  Plotly.purge(plContainer);
  Plotly.plot(plContainer, data, layout);
}

//////////////////////////////////////////////////
/// Zoom chart

function zoomChartData(hdr, rows) {

  var plData = hdr.slice(1).map(function(hdr, ix){
    return {
      x: unpack(rows, 0).map(function(u){ return new Date(u) }),
      y: unpack(rows, ix+1),
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
     x: unpack(rows, ix + 1),
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
/// Common

function displayChart(action, hdr, rows) {
  var plData = null;
  switch (action) {
    case "hist":
      createVarSelectInput();
      plData = histogramChartData(hdr, rows, gl_DefaultHistogramVar);
      plotlyChart(plData);
    break;
    case "zoom":
      plData = zoomChartData(hdr, rows);
      plotlyChart(plData);
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

