#!/system/bin/sh
# NEO TV · optimizador de Android TV / Google TV
#
# Corre DENTRO de la TV (usuario "shell" de ADB). No necesita root.
# Todo lo que desactiva queda anotado en la TV y se puede deshacer con "revertir".
#
#   sh optimizar.sh lista              apps que puede desactivar y su estado
#   sh optimizar.sh aplicar [paquetes] desactiva (sin paquetes = las recomendadas),
#                                      baja animaciones a 0.5 y limpia caché
#   sh optimizar.sh cache              solo limpia la caché de las apps
#   sh optimizar.sh revertir           deja todo como estaba antes de "aplicar"
#   sh optimizar.sh estado             qué hizo este script en esta TV

DIR=/sdcard/neo-optimizar
REG=$DIR/registro.txt
ANIM=$DIR/animaciones.txt

# paquete|grupo|recomendada(1/0)|descripción
# Solo apps que la TV no necesita para funcionar. Nunca se tocan: Play Store, Google Play
# Services, ajustes, teclado, Wi-Fi/Bluetooth, sintonizador/HDMI ni la pantalla de inicio actual.
CATALOGO='tv.anoki.acr.controller|rastreo|1|Rastreador ACR: reconoce lo que ves para mostrarte publicidad
com.google.android.feedback|rastreo|1|Envia reportes de errores a Google
fusion.android.tv.demo|sistema|1|Modo demostracion de tienda
com.android.printspooler|sistema|1|Servicio de impresoras (una TV no imprime)
com.google.android.play.games|sistema|1|Google Play Juegos
com.android.dreams.basic|sistema|1|Protector de pantalla basico
com.amazon.amazonvideo.livingroom|preinstalada|0|Prime Video
com.google.android.youtube.tvmusic|preinstalada|0|YouTube Music
com.google.android.videos|preinstalada|0|Google Play Peliculas
com.cltv.fast|preinstalada|0|Canales gratis con publicidad
com.google.android.marvin.talkback|accesibilidad|0|Lector de pantalla TalkBack (no la quites si alguien en casa lo usa)'

inicio_actual() {
    cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null \
        | tail -1 | cut -d/ -f1
}

instalada() { pm path "$1" >/dev/null 2>&1; }

estado_de() {
    if grep -q "^$1|" "$REG" 2>/dev/null; then echo desactivada-por-neo
    elif ! instalada "$1"; then echo no-instalada
    elif pm list packages -d 2>/dev/null | grep -qx "package:$1"; then echo desactivada
    else echo activa
    fi
}

libre_mb() { df /data 2>/dev/null | tail -1 | awk '{print int($4/1024)}'; }

lista() {
    echo "$CATALOGO" | while IFS='|' read -r pkg grupo rec desc; do
        st=$(estado_de "$pkg" </dev/null)
        [ "$st" = no-instalada ] && continue
        echo "$pkg|$grupo|$rec|$st|$desc"
    done
}

en_catalogo() { echo "$CATALOGO" | grep -q "^$1|"; }

desactivar() {
    pkg=$1
    if ! en_catalogo "$pkg"; then echo "OMITIDA|$pkg|no esta en el catalogo"; return; fi
    if [ "$pkg" = "$(inicio_actual)" ]; then echo "OMITIDA|$pkg|es la pantalla de inicio"; return; fi
    case $(estado_de "$pkg") in
        no-instalada) echo "OMITIDA|$pkg|no esta instalada"; return ;;
        desactivada-por-neo|desactivada) echo "YA|$pkg"; return ;;
    esac
    # disable-user se deshace igual que se hace; algunas apps del sistema no lo permiten
    # y ahí se usa uninstall -k --user 0 (la app sigue en el sistema, solo se oculta)
    if pm disable-user --user 0 "$pkg" 2>&1 | grep -q disabled; then
        echo "$pkg|disable" >> "$REG"; echo "OK|$pkg"
    elif pm uninstall -k --user 0 "$pkg" 2>&1 | grep -q Success; then
        echo "$pkg|uninstall" >> "$REG"; echo "OK|$pkg"
    else
        echo "FALLO|$pkg"
    fi
}

animaciones() {
    if [ ! -f "$ANIM" ]; then
        for k in window_animation_scale transition_animation_scale animator_duration_scale; do
            v=$(settings get global $k); [ "$v" = null ] && v=1
            echo "$k=$v" >> "$ANIM"
        done
    fi
    for k in window_animation_scale transition_animation_scale animator_duration_scale; do
        settings put global $k 0.5
    done
    echo "ANIM|0.5"
}

cache() {
    antes=$(libre_mb)
    pm trim-caches 999G >/dev/null 2>&1
    despues=$(libre_mb)
    echo "CACHE|$((despues - antes))|$despues"
}

aplicar() {
    mkdir -p "$DIR"
    if [ $# -eq 0 ]; then
        set -- $(echo "$CATALOGO" | awk -F'|' '$3==1 {print $1}')
    fi
    for p in "$@"; do desactivar "$p"; done
    animaciones
    cache
}

revertir() {
    if [ -f "$REG" ]; then
        while IFS='|' read -r pkg metodo; do
            [ -z "$pkg" ] && continue
            if [ "$metodo" = uninstall ]; then cmd package install-existing "$pkg" </dev/null >/dev/null 2>&1
            else pm enable --user 0 "$pkg" </dev/null >/dev/null 2>&1
            fi
            echo "OK|$pkg"
        done < "$REG"
        rm -f "$REG"
    fi
    if [ -f "$ANIM" ]; then
        while IFS='=' read -r k v; do settings put global "$k" "$v" </dev/null; done < "$ANIM"
        rm -f "$ANIM"
        echo "ANIM|restauradas"
    fi
}

estado() {
    echo "INICIO|$(inicio_actual)"
    echo "LIBRE|$(libre_mb)"
    echo "RAM|$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)"
    [ -f "$REG" ] && sed 's/^/DESACTIVADA|/' "$REG"
    [ -f "$ANIM" ] && echo "ANIM|0.5"
    return 0
}

accion=$1; [ $# -gt 0 ] && shift
case $accion in
    lista) lista ;;
    aplicar) aplicar "$@" ;;
    cache) cache ;;
    revertir) revertir ;;
    estado) estado ;;
    *) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
