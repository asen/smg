

var gl_sModalObj = {

    sModal: null,
    sModalTitle: null,
    sModalBody: null,

    sModalIsRunning: false,

    sModalHide: function() {
        if (this.sModal == null) return;
        this.sModal.style.display = "none";
        this.sModalIsRunning = false
    },

//    sModalDisplayData: function(data){
//        var ret = "<ul>\n";
//        for(ix in data) {
//            var obj = data[ix]
//            ret += "<li>\n";
//            ret += obj["n"]["ms"]["id"] + "<br/>\n";
//            ret += JSON.stringify(obj["n"]["ms"])
//            if (obj["c"].length > 0) {
//                ret += this.sModalDisplayData(obj["c"])
//            }
//            ret += "</li>\n";
//        }
//        ret += "</ul>\n";
//        return ret
//    },

    sModalLoadData: function(stateIds, bodyElem){
//        var savedThis = this;
        bodyElem.textContent = "Loading ...";
        var idss = JSON.stringify(stateIds)

        var onData = function(data) {
//            console.log(data);
            bodyElem.innerHTML = data; //savedThis.sModalDisplayData(data)
        };
        var onFail = function(err){
            console.log(err)
            bodyElem.textContent = "Error: " + err;
        };
        $.ajax({
            type: "POST",
            url: "/monitor/details",
//            dataType: 'json',
            data: idss,
            contentType: 'application/json',
            success: onData,
            error: onFail
        })


    },

    sModalShow: function(title, stateIds) {
        if (this.sModal == null) return;
        if (this.sModalIsRunning) return;
        this.sModalIsRunning = true;
        this.sModalTitle.textContent = atob(title);
        this.sModalLoadData(stateIds, this.sModalBody)
        this.sModal.style.display = "block";
    },

    init: function() {
        this.sModal = document.getElementById("theSModal");
        this.sModalTitle = document.getElementById("theSModalTitle");
        this.sModalBody = document.getElementById("theSModalBody");

        // Get the <span> element that closes the modal
        var sModalClose = document.getElementById("theSModalClose");
        if (sModalClose != null){
            // setup close event handlers
            var savedThis = this;
            // When the user clicks on <span> (x), close the modal
            sModalClose.onclick = function() {
                savedThis.sModalHide();
            }

            // When the user clicks anywhere outside of the modal, close it
            window.onclick = function(event) {
                if (event.target == savedThis.sModal) {
                    savedThis.sModalHide();
                }
            }
        }
    }
}



function sModalShow(title, stateIds) {
  gl_sModalObj.sModalShow(title, stateIds)
}

// init after body has loaded
$(function() { gl_sModalObj.init() });

