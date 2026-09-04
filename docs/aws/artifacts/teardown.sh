#!/bin/bash
# Teardown — zero the hourly-billed resources. Order matters (dependencies).
# Run from any shell with the ethan-admin CLI profile. Review each block before
# running the first time; after that it's the end-of-session ritual (decision P2).
#
# What it KEEPS (free or pennies): ECR images, S3 bucket + build, CloudFront
# distribution (free when idle), Parameter Store secrets, IAM roles, VPC + SGs,
# CloudWatch logs/dashboard, budgets. Redeploy = Phases 4 + 6 only (~30 min).
#
# What it DESTROYS: ECS service+tasks, ALB, RDS (final snapshot taken), ElastiCache
# (cache data is disposable), Kafka EC2 (BROKER DATA DIES with the instance — fine:
# outbox + processed_events tables in Postgres are the durable record; a fresh broker
# auto-recreates topics via KafkaTopicConfig on next app boot).
set -euo pipefail
REGION=us-east-2
CLUSTER=ticket-reservation
SERVICE=ticket-reservation-app
ALB_NAME=ticket-alb
TG_NAME=ticket-app-tg
RDS_ID=ticket-reservation-db          # <-- match what you named it in Phase 4
CACHE_ID=ticket-reservation-redis     # <-- match Phase 4 (this is the REPLICATION GROUP id; the node is ticket-reservation-redis-001)
KAFKA_TAG="Name=tag:Name,Values=kafka-broker"  # <-- tag the EC2 'Name=kafka-broker' at launch

echo "== 1/5 ECS service -> 0 and delete"
aws ecs update-service --region $REGION --cluster $CLUSTER --service $SERVICE --desired-count 0
aws ecs delete-service  --region $REGION --cluster $CLUSTER --service $SERVICE --force

echo "== 2/5 ALB + target group"
ALB_ARN=$(aws elbv2 describe-load-balancers --region $REGION --names $ALB_NAME --query 'LoadBalancers[0].LoadBalancerArn' --output text)
aws elbv2 delete-load-balancer --region $REGION --load-balancer-arn "$ALB_ARN"
aws elbv2 wait load-balancers-deleted --region $REGION --load-balancer-arns "$ALB_ARN"
TG_ARN=$(aws elbv2 describe-target-groups --region $REGION --names $TG_NAME --query 'TargetGroups[0].TargetGroupArn' --output text)
aws elbv2 delete-target-group --region $REGION --target-group-arn "$TG_ARN"

echo "== 3/5 Kafka EC2 (terminate — data is disposable, see header)"
KAFKA_ID=$(aws ec2 describe-instances --region $REGION --filters "$KAFKA_TAG" "Name=instance-state-name,Values=running,stopped" --query 'Reservations[].Instances[].InstanceId' --output text)
[ -n "$KAFKA_ID" ] && aws ec2 terminate-instances --region $REGION --instance-ids $KAFKA_ID

echo "== 4/5 RDS (final snapshot — restore from it next session instead of re-seeding)"
aws rds delete-db-instance --region $REGION --db-instance-identifier $RDS_ID \
  --final-db-snapshot-identifier "$RDS_ID-$(date +%Y%m%d-%H%M)"

echo "== 5/5 ElastiCache (Valkey was created as a replication group, not a standalone node — delete-cache-cluster returns CacheClusterNotFound)"
aws elasticache delete-replication-group --region $REGION --replication-group-id $CACHE_ID --no-retain-primary-cluster

echo "Done. Billing tail: RDS/ElastiCache take ~10 min to delete. Check the console"
echo "Billing page tomorrow — the only recurring lines left should be pennies (S3/EBS snapshots)."
