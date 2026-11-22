#!/bin/sh
# Cria os tópicos da seção 4.2 do Pacote 2 antes de qualquer serviço subir (sem auto-criação).
set -eu
KT=/opt/kafka/bin/kafka-topics.sh
BS=${KAFKA_BOOTSTRAP:-kafka:19092}
RF=${KAFKA_REPLICATION:-1}
criar() { "$KT" --bootstrap-server "$BS" --create --if-not-exists --topic "$1" --partitions "$2" --replication-factor "$RF" --config retention.ms="$3"; }
DIA=86400000
criar inventario.reserva.v1 6 $((7*DIA))
criar inventario.assento.v1 6 $((1*DIA))
criar checkout.pedido.v1 6 $((7*DIA))
criar emissao.ingresso.v1 3 $((7*DIA))
# Pacote 4: identidade (aprovação de organizador) e navegação (telemetria)
criar iam.usuario.v1 3 $((7*DIA))
criar telemetria.navegacao.v1 6 $((1*DIA))
# DLQs: <topico>.<grupo>.dlq, 1 partição, 14 dias
criar inventario.reserva.v1.checkout-inventario.reserva.v1.dlq 1 $((14*DIA))
criar checkout.pedido.v1.inventario-checkout.pedido.v1.dlq 1 $((14*DIA))
criar checkout.pedido.v1.emissao-checkout.pedido.v1.dlq 1 $((14*DIA))
# o grupo da telemetria lê os cinco tópicos de domínio e navegação
for t in inventario.reserva.v1 checkout.pedido.v1 emissao.ingresso.v1 iam.usuario.v1 telemetria.navegacao.v1; do
  criar "$t.telemetria-dominio.dlq" 1 $((14*DIA))
done
"$KT" --bootstrap-server "$BS" --list
