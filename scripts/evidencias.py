#!/usr/bin/env python3
"""Coleta as evidências de teste dos relatórios já gerados (surefire, failsafe, JaCoCo, PIT) e grava
docs/evidencias/pacote4-testes.json. Rode depois de `./mvnw verify` (e do PIT, se quiser a mutação).

Uso: python3 scripts/evidencias.py
"""
import csv
import glob
import json
import os
import xml.etree.ElementTree as ET

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULOS = ["libs/commons", "services/api-gateway", "services/inventario-service", "services/checkout-service",
           "services/emissao-service", "services/gateway-pagamento", "services/iam-service",
           "services/telemetria-service", "services/realtime-service"]


def contar(padrao):
    total = falhas = erros = ignorados = 0
    for arq in glob.glob(padrao):
        s = ET.parse(arq).getroot()
        total += int(s.get("tests", 0)); falhas += int(s.get("failures", 0))
        erros += int(s.get("errors", 0)); ignorados += int(s.get("skipped", 0))
    return {"testes": total, "falhas": falhas + erros, "ignorados": ignorados}


def cobertura_dominio(modulo):
    csv_ = os.path.join(RAIZ, modulo, "target/site/jacoco/jacoco.csv")
    if not os.path.exists(csv_):
        return None
    perdidas = cobertas = 0
    with open(csv_, encoding="utf8") as f:
        for linha in csv.DictReader(f):
            if linha["PACKAGE"].endswith(".domain"):
                perdidas += int(linha["LINE_MISSED"]); cobertas += int(linha["LINE_COVERED"])
    return round(100 * cobertas / (perdidas + cobertas), 1) if perdidas + cobertas else None


def mutacao(modulo):
    base = os.path.join(RAIZ, modulo, "target/pit-reports")
    relatorios = sorted(glob.glob(os.path.join(base, "mutations.xml")) or glob.glob(os.path.join(base, "*/mutations.xml")))
    if not relatorios:
        return None
    mortos = total = 0
    for m in ET.parse(relatorios[-1]).getroot().iter("mutation"):
        total += 1
        mortos += m.get("status") == "KILLED"
    return {"mortos": mortos, "total": total}


saida = {"modulos": {}}
for mod in MODULOS:
    base = os.path.join(RAIZ, mod, "target")
    unit = contar(os.path.join(base, "surefire-reports/TEST-*.xml"))
    integ = contar(os.path.join(base, "failsafe-reports/TEST-*.xml"))
    saida["modulos"][mod] = {"unitarios": unit, "integracao": integ,
                             "cobertura_dominio_linhas": cobertura_dominio(mod), "mutacao": mutacao(mod)}

tot = lambda chave, campo: sum(m[chave][campo] for m in saida["modulos"].values())
saida["totais"] = {"unitarios": tot("unitarios", "testes"), "integracao": tot("integracao", "testes"),
                   "falhas": tot("unitarios", "falhas") + tot("integracao", "falhas")}
destino = os.path.join(RAIZ, "docs/evidencias/pacote4-testes.json")
os.makedirs(os.path.dirname(destino), exist_ok=True)
with open(destino, "w", encoding="utf8") as f:
    json.dump(saida, f, indent=2, ensure_ascii=False)
    f.write("\n")
print(json.dumps(saida["totais"]), "->", os.path.relpath(destino, RAIZ))
for mod, m in saida["modulos"].items():
    print(f"  {mod:34s} unit={m['unitarios']['testes']:3d} integ={m['integracao']['testes']:3d} "
          f"dominio={m['cobertura_dominio_linhas']} pit={m['mutacao']}")
