
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
//        console.log(elem)
//        console.log(elem.checked)
//        console.log(oid)
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
            obj.checked = myself.isSelected(oid)
        });
        $('.btnseldisp').each(function(i, obj) {
            var oid = obj.id.replace(/^btnsel_/, "")
            if (myself.isSelected(oid)){
                $(this).show();
            } else {
                $(this).hide();
            }
        });
//        console.log("refresh: " + str)
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
      this.setStoredObj(selId, cur)
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
      this.setStoredObj(selId, cur)
    },

    getOids: function(){
      return this.listSelection(this.curSel);
    },

    getLocalIds: function(){
      return this.getOids().map(function(oid){
        if (oid.startsWith("@")) {
          var ix = oid.indexOf(".")
          return oid.slice(ix + 1)
        } else {
          return oid
        }

      })
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
