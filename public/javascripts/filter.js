

var gl_searchHistoryManager = {

    MAX_HISTORY: 50,

    keyPrefix: 'smg_autocomplete_',

    init: function() {
        // console.log("init")
    },

    addHistory: function(elemId) {
      var elem = document.getElementById(elemId)
      if (elem) {
        var val = elem.value
        if (val) {
          this.addHistoryItem(elemId, val)
        }
      }
    },

    getHistory: function(elemId) {
      return this.getStoredObj(elemId)
    },

    // private
    addHistoryItem: function(elemId, val) {
      var cur = this.getStoredObj(elemId)
      if (!cur) {
        cur = []
      }
      var existingIdx = cur.indexOf(val)
      if ( existingIdx >= 0) {
        cur.splice(existingIdx, 1)
      }
      cur.unshift(val)
      if (cur.length > this.MAX_HISTORY) {
        cur = cur.slice(0, this.MAX_HISTORY)
      }
      this.setStoredObj(elemId, cur)
    },

    getStoredObj: function(key) {
      var ret = localStorage.getItem(this.keyPrefix + key)
      if (ret) {
        return JSON.parse(ret)
      } else {
       return []
      }
    },

    setStoredObj: function(key, val) {
      localStorage.setItem(this.keyPrefix + key, JSON.stringify(val));
    },
}

$(function() { gl_searchHistoryManager.init() });


function smgAutocompleteSetup(inputId, dataUrl) {
   var input = document.getElementById(inputId);
   var SEPARATOR = "___AUTOCOMPLETE_SEPARATOR___"
   $( "#" + inputId ).autocomplete({
     source: function( request, response ) {
       var MAX_LOCAL_ITEMS = 15;
       var MAX_REMOTE_ITEMS = 15;
       var remoteElem = document.getElementById('smg-filter-remote');
       var remoteId = "";
       if (remoteElem) {
         remoteId = remoteElem.value
       }

       var onData = function(data) {
           var localData = gl_searchHistoryManager.getHistory(inputId)
           var historyResults = $.ui.autocomplete.filter(localData, request.term).slice(0, MAX_LOCAL_ITEMS);
           var results = $.ui.autocomplete.filter(data, request.term).slice(0, MAX_REMOTE_ITEMS);
           if ( (historyResults.length > 0) && (results.length > 0) ) {
             historyResults.push(SEPARATOR)
           }
           var resp = historyResults.concat(results).map(function(s){
                       var val = (s == SEPARATOR) ? "" : s;
                       return {
                          label: s,
                          value: val
                       }
                      });
           //console.log(resp);
           response(resp);
         }

       if (dataUrl == "") {
         // local-only autocomplete
         onData([])
       } else {
         $.get(dataUrl,
           { q: request.term, remote: remoteId },
           onData
         ). //get
         fail(function(err){
          response([]);
         }); //fail
       }
     }
     ,minLength: 0
   }) // autocomplete
   .autocomplete( "instance" )._renderItem = function( ul, item ) {
         //console.log(item);
         var lbl = item.label == SEPARATOR ? "<hr/>" : item.label
         return $( "<li>" )
                 .attr( "data-value", item.value )
                 .append( "<div>" + lbl + "</div>" )
                 .appendTo( ul );
       }
   ;
}

function clearElem(elemId) {
  var elem = document.getElementById(elemId)
  if (elem){
    elem.value = "";
    elem.focus();
  }
}
