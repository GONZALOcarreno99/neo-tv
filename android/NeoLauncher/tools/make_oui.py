"""Genera assets/oui.txt.gz (fabricante por MAC) desde la lista "manuf" de Wireshark.

Uso: python tools/make_oui.py manuf assets/oui.txt.gz
La lista se descarga de https://www.wireshark.org/download/automated/data/manuf
Solo se guardan los bloques de 24 bits (los 3 primeros bytes de la MAC), con el nombre acortado.
"""
import gzip
import re
import sys

src, dst = sys.argv[1], sys.argv[2]
out = {}
for line in open(src, encoding="utf-8", errors="ignore"):
    if line.startswith("#") or not line.strip():
        continue
    parts = line.rstrip("\n").split("\t")
    pre = parts[0].strip()
    if not re.fullmatch(r"[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}", pre):
        continue
    name = (parts[2] if len(parts) > 2 and parts[2].strip() else parts[1]).strip()
    name = re.sub(r"\s*(,?\s*(Co\.?,?\s*)?(Ltd\.?|LTD\.?|Inc\.?|Corp\.?|Corporation|Limited|GmbH|S\.?A\.?|LLC|AG|B\.V\.))+\.?$",
                  "", name).strip(" ,.")
    out[pre.replace(":", "").upper()] = name[:40]
data = "".join(f"{k}\t{v}\n" for k, v in sorted(out.items())).encode()
with gzip.open(dst, "wb", 9) as f:
    f.write(data)
print(len(out), "fabricantes")
