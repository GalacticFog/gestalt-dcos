function disableShutdownButtons(state) {
    $('#shutdownButton').prop('disabled', state);
    $('#shutdownSvcsAndDB').prop('disabled', state);
    $('#shutdownSvcsOnly').prop('disabled', state);
}

$('#restartButton').on('click', function() {
    this.blur();
    console.log('restarting all services');
    $.post( "restart", function(data) {
        console.log('restart response: ' + data.message);
        doPoll();
    });
});

$('#shutdownSvcsAndDB').on('click', function () {
    console.log('shutting down all services');
    $('#shutdownModal').modal('hide')
    $.post( "shutdown?shutdownDB=true", function(data) {
        console.log('shutdown response: ' + data.message);
        doPoll();
    });
});
$('#shutdownSvcsOnly').on('click', function () {
    console.log('shutting down all services except DB');
    $('#shutdownModal').modal('hide')
    $.post( "shutdown?shutdownDB=false", function(data) {
        console.log('shutdown response: ' + data.message);
        doPoll();
    });
});

function doPoll(){
    $.getJSON("data", function(data) {
        setTimeout(doPoll,2000);
        if (data.error) {
            $('#launcher-error').html(data.error).removeClass('hidden');
        } else {
            $('#launcher-error').html("").addClass('hidden');
        }
        if (data.isConnectedToMarathon) {
            $('#marathon-connection').html("Connected").removeClass("connected-false").addClass("connected-true")
        } else {
            $('#marathon-connection').html("Disconnected").removeClass("connected-true").addClass("connected-false")
        }
        $('#launcher-stage').html(data.launcherStage);
        populateTable(data.services);
        if (data.launcherStage === "ShuttingDown" || data.launcherStage == "Uninitialized") {
            $('#restartButton').prop('disabled',false);
        } else {
            $('#restartButton').prop('disabled',true);
        }
    });
}

function build_vhost_string(svc) {
    if (svc.vhosts === null) return '';
    var vhosts = [];
    $.each(svc.vhosts, function (i,vhost) {
        url = vhost;
        vhosts.push(
            '<a target="_blank" href="' + url + '">' + url + '</a>'
        );
    });
    return vhosts.join("<br>");
}

function build_url_string(svc) {
    if (svc.hostname && svc.ports.length > 0) {
        if (svc.ports.length == 1) {
            u = 'http://' + svc.hostname + ':' + svc.ports[0];
            return '<a target="_blank" href="' + u + '">' + u + '</a>';
        } else {
            var url_list = [];
            $.each(svc.ports, function (i,p) {
                u = 'http://' + svc.hostname + ':' + p;
                url_list.push(
                    '<a target="_blank" href="' + u + '">' + p + '</a>'
                )
            });
            return 'http://' + svc.hostname + ':[' + url_list.join(",") + ']';
        }
    }
    return '';
}

function populateTable(services) {
    var items = [];
    $.each(services, function (i,svc) {
        vhosts = build_vhost_string(svc);
        urls = build_url_string(svc);
        items.push( '<tr><td>' + svc.serviceName + '</td><td>' + vhosts + '</td><td>' + urls + '</td><td><div class="' + svc.status.toLowerCase() + '">' + svc.status + '</div></td></tr>' );
    });
    $('#dataBody').html( items.join("") );
}

doPoll();
