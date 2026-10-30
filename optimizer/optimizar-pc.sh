#!/usr/bin/env bash
# NEO TV · optimizar la TV desde una Mac o Linux
# Uso:  ./optimizar-pc.sh [IP] [--revertir]
set -e
cd "$(dirname "$0")"

if ! command -v adb >/dev/null; then
    echo "No encuentro 'adb'. Instálalo:"
    echo "  Mac:   brew install android-platform-tools"
    echo "  Linux: sudo apt install adb"
    exit 1
fi

IP=$1; MODO=$2
[ "$IP" = "--revertir" ] && { MODO=$IP; IP=; }
[ -z "$IP" ] && read -rp "IP de la TV (Ajustes > Red > Wi-Fi en la TV): " IP
DEV="$IP:5555"

echo "Conectando con $DEV ... si en la TV aparece un aviso, elige 'Permitir siempre'."
adb connect "$DEV"
for _ in $(seq 30); do [ "$(adb -s "$DEV" get-state 2>/dev/null)" = device ] && break; sleep 1; done
[ "$(adb -s "$DEV" get-state 2>/dev/null)" = device ] || { echo "La TV no respondió. ¿Activaste la depuración por red?"; exit 1; }

adb -s "$DEV" push optimizar.sh /data/local/tmp/optimizar.sh >/dev/null
tv() { adb -s "$DEV" shell "sh /data/local/tmp/optimizar.sh $*" 2>/dev/null | tr -d '\r' | grep '|' || true; }

if [ "$MODO" = "--revertir" ]; then
    tv revertir | sed 's/^/  /'
    echo "Listo: la TV quedó como antes de optimizar."
    exit 0
fi

ELEGIDAS=""
while IFS='|' read -r pkg grupo rec estado que; do
    [ "$estado" = activa ] || continue
    def=N; [ "$rec" = 1 ] && def=S
    read -rp "¿Desactivar $pkg? $que [S/N, Enter = $def] " r </dev/tty
    r=${r:-$def}
    case $r in s*|S*) ELEGIDAS="$ELEGIDAS $pkg" ;; esac
done <<< "$(tv lista)"

echo; echo "Aplicando..."
tv aplicar $ELEGIDAS | while IFS='|' read -r tipo a b; do
    case $tipo in
        OK) echo "  desactivada  $a" ;;
        FALLO) echo "  no se pudo   $a" ;;
        ANIM) echo "  animaciones  0.5" ;;
        CACHE) echo "  caché        +$a MB  ($b MB libres)" ;;
    esac
done
echo; echo "Listo. Para deshacer todo:  ./optimizar-pc.sh $IP --revertir"
