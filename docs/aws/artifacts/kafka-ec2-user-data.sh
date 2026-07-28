#!/bin/bash
# User-data for the Kafka broker EC2 (t4g.small, Amazon Linux 2023 arm64).
# Same apache/kafka:4.0.0 single-node KRaft broker as docker-compose, with one
# critical difference: ADVERTISED_LISTENERS uses the instance's PRIVATE IP so
# Fargate tasks that bootstrap here get told to reconnect to a reachable address.
# Data persists on the instance's EBS volume via the bind mount; a stop/start
# keeps it, termination loses it (acceptable: outbox + dedup tables make the
# saga loop recoverable, and this is a portfolio broker — the ADR says MSK in prod).
set -euxo pipefail

dnf install -y docker
systemctl enable --now docker

PRIVATE_IP=$(curl -s http://169.254.169.254/latest/meta-data/local-ipv4)
mkdir -p /var/lib/kafka-data
chown 1000:1000 /var/lib/kafka-data

docker run -d \
  --name broker \
  --restart unless-stopped \
  -p 9092:9092 \
  -v /var/lib/kafka-data:/var/lib/kafka/data \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://localhost:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://${PRIVATE_IP}:9092 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_LOG_DIRS=/var/lib/kafka/data \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
  -e KAFKA_NUM_PARTITIONS=3 \
  apache/kafka:4.0.0

# Verify from a shell later:  sudo docker exec broker /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
