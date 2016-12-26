

function smgRemoteId(oid) {
  if (oid.startsWith("@")) {
    var ix = oid.indexOf(".")
    return oid.slice(0, ix)
  } else {
    return ""
  }
}

function smgLocalId(oid){
  if (oid.startsWith("@")) {
    var ix = oid.indexOf(".")
    return oid.slice(ix + 1)
  } else {
    return oid
  }
}

function smgCompareStrs(a,b) {
  if (a == b) {
    return 0
  } else if (a > b) {
    return 1
  } else {
    return -1
  }
}

var gl_dispSelManager = {

    curSel: "default",

    init: function() {
        // console.log("init")
        // TODO init curSel when more than 1 are supported
        this.refresh();
    },

    listSelections: function() {
      return [ "default" ]; // TODO
    },

    isSelected: function(oid) {
      var cur = this.listSelection(this.curSel)
      return cur.indexOf(oid) > -1
    },

    onCbClick: function(elem, oid) {
      if (elem.checked) {
        this.addSelected(this.curSel, oid)
      } else {
        this.removeSelected(this.curSel, oid)
      }
      this.refresh();
    },

    clear: function() {
      this.setStoredObj(this.curSel, []);
      this.refresh();
    },

    refresh: function() {
        var oids = this.getOids().map(this.oidShowUrlHtml)
        var str = "Nothing is selected"
        if (oids.length > 0) {
          str = oids.join(" | ");
          $("#gl_dispSelManager_clear_btn").show();
          $("#gl_dispSelManager_display_btn").show();
        } else {
          $("#gl_dispSelManager_clear_btn").hide();
          $("#gl_dispSelManager_display_btn").hide();
        }
        var myself = this;
        $('.cbseldisp').each(function(i, obj) {
            var oid = obj.id.replace(/^cbsel_/, "")
            var isSelected = myself.isSelected(oid)
            obj.checked = isSelected
            var btnElem =  document.getElementById("btnsel_" + oid)
            if (btnElem){
              var vis = "hidden"
              if (isSelected) {
                vis = "visible"
              }
              btnElem.style.visibility = vis;
            }
        });
        $("#selected-for-display-list").html(str)
    },

    display: function() {
        var oids = this.getLocalIds()
        if (oids.length > 0){
          //rx=^(oid1|oid2|...)$
          window.location = "/dash?remote=*&rx=%5E%28" + oids.join("%7C") + "%29%24"
        }
    },

    // private
    addSelected: function(selId, oid) {
      var cur = this.getStoredObj(selId)
      if (!cur) {
        cur = []
      }
      if (cur.indexOf(oid) < 0) {
        cur.push(oid)
      }
      this.setStoredObj(selId, cur.sort(this.compareOids))
    },

    removeSelected: function(selId, oid) {
      var cur = this.getStoredObj(selId)
      if (!cur) {
        return
      }
      var ix = cur.indexOf(oid)
      if (ix < 0){
        return
      }
      cur.splice(ix,1)
      this.setStoredObj(selId, cur.sort(this.compareOids));
    },

    getOids: function(){
      return this.listSelection(this.curSel);
    },

    compareOids: function(a,b) {
      var ra = smgRemoteId(a);
      var rb = smgRemoteId(b);
      var la = smgLocalId(a);
      var lb = smgLocalId(b);
      if (ra == rb) {
        return smgCompareStrs(la, lb)
      } else {
        return smgCompareStrs(ra, rb)
      }
    },

    getLocalIds: function(){
      return this.getOids().map(smgLocalId)
    },

    listSelection: function(selId) {
      var cur = this.getStoredObj(selId)
      if (!cur) {
        return []
      }
      return cur
    },

    oidShowUrlHtml: function(oid){
        return '<a href="/show/' + oid + '">' + oid + '</a>'
    },

    keyPrefix: 'smg_dispsel_',

    getStoredObj: function(key) {
      var ret = localStorage.getItem(this.keyPrefix + key)
      if (ret) {
        return JSON.parse(ret)
      } else {
       return ret
      }
    },

    setStoredObj: function(key, val) {
      localStorage.setItem(this.keyPrefix + key, JSON.stringify(val));
    },
}

//window.onload = gl_dispSelManager.init

$(function() { gl_dispSelManager.init() });
