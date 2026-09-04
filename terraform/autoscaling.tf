# Autoscaling.
#
# Es la parte que no se traduce sola. En la Fase 1, KEDA hablaba Redis desde el
# operador, calculaba XLEN/10 y se lo daba a un HPA que hacía el resto. En ECS
# no existe nada que lea Redis, así que la cadena queda en tres piezas:
#
#   Lambda cada minuto  ->  métrica en CloudWatch  ->  alarma  ->  step scaling
#
# Y hay dos diferencias con KEDA que conviene tener presentes porque se notan:
#
# 1. Cadencia. KEDA consultaba cada 10s (pollingInterval). EventBridge no baja
#    de 1 minuto y la alarma necesita al menos un período de 60s, así que entre
#    que la cola crece y aparece la primera tarea nueva pasan 1-2 minutos contra
#    los ~15s de allá. Para jobs que tardan minutos es aceptable; para una cola
#    de latencia baja no lo sería.
#
# 2. Step scaling y no target tracking. Target tracking es lo que hacía el HPA
#    —mantener backlog/tareas en 10— pero para eso necesita una métrica *por
#    tarea*, y saber cuántas tareas corren implica o activar Container Insights
#    (que se cobra) o darle a la Lambda permiso sobre la API de ECS y una salida
#    para llegar a ella (otro endpoint de interfaz, otros 7 USD/mes). Step
#    scaling sobre el backlog total evita las dos cosas. Lo que se pierde es la
#    convergencia suave: en vez de acercarse al objetivo, salta de escalón en
#    escalón.

resource "aws_appautoscaling_target" "worker" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.worker.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.worker_min_count
  max_capacity       = var.worker_max_count
}

resource "aws_appautoscaling_policy" "worker_out" {
  name               = "${local.name}-worker-out"
  policy_type        = "StepScaling"
  service_namespace  = aws_appautoscaling_target.worker.service_namespace
  resource_id        = aws_appautoscaling_target.worker.resource_id
  scalable_dimension = aws_appautoscaling_target.worker.scalable_dimension

  step_scaling_policy_configuration {
    adjustment_type = "ChangeInCapacity"
    # Corto, porque una tarea de Fargate tarda ~40s en estar lista y no queremos
    # disparar el segundo escalón antes de que la primera empiece a drenar.
    cooldown                = 60
    metric_aggregation_type = "Average"

    # Los bounds son relativos al umbral de la alarma, no absolutos.
    # Con scale_out_threshold = 20: de 20 a 120 suma 2 tareas, de 120 para
    # arriba suma 4. Un pico grande no se atiende de a una.
    step_adjustment {
      metric_interval_lower_bound = 0
      metric_interval_upper_bound = 100
      scaling_adjustment          = 2
    }

    step_adjustment {
      metric_interval_lower_bound = 100
      scaling_adjustment          = 4
    }
  }
}

resource "aws_appautoscaling_policy" "worker_in" {
  name               = "${local.name}-worker-in"
  policy_type        = "StepScaling"
  service_namespace  = aws_appautoscaling_target.worker.service_namespace
  resource_id        = aws_appautoscaling_target.worker.resource_id
  scalable_dimension = aws_appautoscaling_target.worker.scalable_dimension

  step_scaling_policy_configuration {
    adjustment_type = "ChangeInCapacity"
    # Largo y asimétrico respecto del scale-out, igual que la stabilization
    # window de 120s del HPA: bajar de golpe manda SIGTERM a workers que están a
    # mitad de un job, y recuperar eso cuesta un ciclo del reclaimer.
    cooldown                = 180
    metric_aggregation_type = "Average"

    step_adjustment {
      metric_interval_upper_bound = 0
      scaling_adjustment          = -1
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "queue_deep" {
  alarm_name          = "${local.name}-queue-deep"
  namespace           = "InferQueue"
  metric_name         = "QueueDepth"
  dimensions          = { Project = var.project }
  statistic           = "Average"
  period              = 60
  evaluation_periods  = 1
  threshold           = var.worker_scale_out_threshold
  comparison_operator = "GreaterThanThreshold"

  # Si la Lambda falla no hay dato, y sin dato no se escala hacia arriba: es
  # preferible a inventar un pico a partir de un fallo del publicador.
  treat_missing_data = "notBreaching"

  alarm_actions = [aws_appautoscaling_policy.worker_out.arn]
}

resource "aws_cloudwatch_metric_alarm" "queue_drained" {
  alarm_name  = "${local.name}-queue-drained"
  namespace   = "InferQueue"
  metric_name = "QueueDepth"
  dimensions  = { Project = var.project }
  statistic   = "Average"
  period      = 60
  # Tres minutos por debajo del umbral antes de devolver una tarea. La cola baja
  # a cero entre lotes todo el tiempo; sin esta espera el servicio oscilaría.
  evaluation_periods  = 3
  threshold           = var.worker_scale_in_threshold
  comparison_operator = "LessThanOrEqualToThreshold"

  # Acá "missing" y no "notBreaching": sin dato tampoco se baja la escala.
  treat_missing_data = "missing"

  alarm_actions = [aws_appautoscaling_policy.worker_in.arn]
}

# --- Gateway -----------------------------------------------------------------
#
# Traducción directa del HPA de CPU de la Fase 1: mismo 70% de objetivo. Acá sí
# hay target tracking, porque la utilización de CPU de un servicio de ECS ya es
# una métrica promediada por tarea y no hay que calcular nada.

resource "aws_appautoscaling_target" "gateway" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.gateway.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.gateway_min_count
  max_capacity       = var.gateway_max_count
}

resource "aws_appautoscaling_policy" "gateway_cpu" {
  name               = "${local.name}-gateway-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.gateway.service_namespace
  resource_id        = aws_appautoscaling_target.gateway.resource_id
  scalable_dimension = aws_appautoscaling_target.gateway.scalable_dimension

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }

    target_value       = 70
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}

# --- El publicador -----------------------------------------------------------

data "archive_file" "queue_depth" {
  type        = "zip"
  source_file = "${path.module}/lambda/queue_depth.py"
  output_path = "${path.module}/.build/queue_depth.zip"
}

resource "aws_lambda_function" "queue_depth" {
  function_name = "${local.name}-queue-depth"
  role          = aws_iam_role.queue_depth.arn
  runtime       = "python3.12"
  handler       = "queue_depth.handler"
  timeout       = 15

  filename         = data.archive_file.queue_depth.output_path
  source_code_hash = data.archive_file.queue_depth.output_base64sha256

  # En subredes privadas: sólo necesita alcanzar ElastiCache y el endpoint de
  # CloudWatch, y ninguno de los dos está en internet.
  vpc_config {
    subnet_ids         = aws_subnet.private[*].id
    security_group_ids = [aws_security_group.lambda.id]
  }

  environment {
    variables = {
      REDIS_HOST = aws_elasticache_cluster.main.cache_nodes[0].address
      REDIS_PORT = tostring(aws_elasticache_cluster.main.cache_nodes[0].port)
      STREAMS    = join(",", local.queue_streams)
      PROJECT    = var.project
    }
  }

  depends_on = [
    aws_iam_role_policy_attachment.queue_depth_vpc,
    aws_vpc_endpoint.monitoring,
    # Si la función se invoca antes de que Terraform cree el log group, Lambda lo
    # crea sola y el apply falla con ResourceAlreadyExists.
    aws_cloudwatch_log_group.queue_depth,
  ]
}

resource "aws_cloudwatch_log_group" "queue_depth" {
  name              = "/aws/lambda/${local.name}-queue-depth"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_event_rule" "queue_depth" {
  name = "${local.name}-queue-depth"
  # El piso de EventBridge. Es lo que fija la latencia de reacción del
  # autoscaler y no hay forma de bajarlo con una rule.
  schedule_expression = "rate(1 minute)"
}

resource "aws_cloudwatch_event_target" "queue_depth" {
  rule = aws_cloudwatch_event_rule.queue_depth.name
  arn  = aws_lambda_function.queue_depth.arn
}

resource "aws_lambda_permission" "queue_depth" {
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.queue_depth.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.queue_depth.arn
}
