# Dos roles por tarea, y la distinción se confunde seguido:
#
#  - el rol de *ejecución* lo usa el agente de ECS antes de que exista el
#    contenedor: bajar la imagen de ECR, resolver los `secrets`, abrir el log
#    group. Si falla, la tarea ni arranca.
#  - el rol de *tarea* lo usa el código de la aplicación una vez corriendo.
#
# Meter los permisos de secretos en el rol de tarea es el error clásico: la
# tarea nunca llega a correr, así que nunca los usa.

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  statement {
    actions = ["ssm:GetParameters"]
    resources = [
      aws_ssm_parameter.db_password.arn,
      aws_ssm_parameter.admin_token.arn,
    ]
  }

  # Un SecureString está cifrado con la clave gestionada aws/ssm, y descifrarlo
  # necesita permiso de KMS además del de SSM. Falta este permiso y el síntoma
  # es una tarea que muere en PROVISIONING con "AccessDeniedException", sin un
  # solo log de la aplicación. La condición limita el descifrado a las llamadas
  # que vienen de SSM.
  statement {
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${var.region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

resource "aws_iam_role" "task" {
  name               = "${local.name}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

# La aplicación no llama a ninguna API de AWS: sigue siendo el mismo binario que
# corre en compose y en kind. Lo único que pide el rol de tarea es el canal de
# ECS Exec, que es el equivalente de `kubectl exec` — abrir una shell dentro de
# una tarea para mirar qué pasa.
data "aws_iam_policy_document" "task_exec_channel" {
  statement {
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "task_exec_channel" {
  name   = "exec-channel"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_exec_channel.json
}

# --- Lambda ------------------------------------------------------------------

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "queue_depth" {
  name               = "${local.name}-queue-depth"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

# Crear y borrar las ENI con las que la Lambda entra a la VPC. Sin esto la
# función queda en estado Pending para siempre.
resource "aws_iam_role_policy_attachment" "queue_depth_vpc" {
  role       = aws_iam_role.queue_depth.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

data "aws_iam_policy_document" "queue_depth_metrics" {
  # PutMetricData no admite restricción por recurso: el permiso es sobre "*" o
  # no es. Se acota por namespace, que es lo único que la API permite filtrar.
  statement {
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = ["InferQueue"]
    }
  }
}

resource "aws_iam_role_policy" "queue_depth_metrics" {
  name   = "put-metric-data"
  role   = aws_iam_role.queue_depth.id
  policy = data.aws_iam_policy_document.queue_depth_metrics.json
}
