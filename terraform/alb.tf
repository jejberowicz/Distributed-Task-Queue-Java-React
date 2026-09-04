# El equivalente del Ingress de la Fase 1: mismo ruteo por path, mismos
# backends, mismo /actuator deliberadamente fuera del alcance público.

resource "aws_lb" "main" {
  name               = local.name
  load_balancer_type = "application"
  subnets            = aws_subnet.public[*].id
  security_groups    = [aws_security_group.alb.id]

  # Lo mismo que los proxy-read-timeout de 3600 de las anotaciones del Ingress.
  # Una conexión STOMP ociosa espera eventos de jobs que pueden tardar minutos;
  # con el default de 60s el ALB la corta y el dashboard reconecta en loop.
  idle_timeout = 3600

  enable_deletion_protection = false
}

resource "aws_lb_target_group" "gateway" {
  name        = "${local.name}-gateway"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip" # awsvpc: cada tarea tiene su propia ENI y su propia IP

  # El health check del target group no pasa por las reglas del listener: pega
  # directo contra la tarea. Por eso puede usar /actuator/health/readiness aunque
  # /actuator no esté ruteado desde afuera — que es justo lo que se quiere.
  health_check {
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # El análogo del `preStop: sleep 5` de Kubernetes, pero al revés de bueno.
  #
  # Kubernetes manda el SIGTERM y borra el endpoint en paralelo, y por eso hay
  # que dormir a mano para no comerse 502 en cada deploy. ECS lo hace en orden:
  # primero desregistra del target group, después espera este delay, y recién
  # entonces manda el SIGTERM. No hace falta el sleep.
  deregistration_delay = 30
}

resource "aws_lb_target_group" "dashboard" {
  name        = "${local.name}-dashboard"
  port        = 80
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"

  health_check {
    path                = "/"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  deregistration_delay = 10
}

# --- Listeners ---------------------------------------------------------------

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  # Con certificado, el 80 sólo redirige. Sin certificado, sirve la aplicación.
  dynamic "default_action" {
    for_each = var.certificate_arn == "" ? [] : [1]
    content {
      type = "redirect"
      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }

  dynamic "default_action" {
    for_each = var.certificate_arn == "" ? [1] : []
    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.dashboard.arn
    }
  }
}

resource "aws_lb_listener" "https" {
  count = var.certificate_arn == "" ? 0 : 1

  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.dashboard.arn
  }
}

# --- Reglas ------------------------------------------------------------------
#
# Tres paths van al gateway y todo lo demás cae en el dashboard, igual que en el
# Ingress. La API va directo al gateway y no atraviesa el nginx del dashboard:
# un hop menos, y el dashboard queda como lo que es, estáticos.
#
# /actuator no tiene regla, así que cae en el default y lo atiende el nginx del
# dashboard, que lo resuelve como una ruta más de la SPA. No hay filtro de API
# key sobre /actuator —sólo cubre /v1—, así que exponerlo sería publicar métricas
# y detalle de health a internet. Se mira con `make exec` o `make logs`.

locals {
  # Sobre el listener que efectivamente sirve tráfico: el 443 si hay
  # certificado, el 80 si no.
  # one() y no [0]: Terraform evalúa las dos ramas del condicional, y indexar
  # una lista vacía sería un error aun cuando esa rama no se elige.
  serving_listener_arn = var.certificate_arn == "" ? aws_lb_listener.http.arn : one(aws_lb_listener.https[*].arn)

  gateway_paths = {
    api   = { priority = 10, pattern = "/v1*" }
    admin = { priority = 20, pattern = "/admin*" }
    ws    = { priority = 30, pattern = "/ws*" }
  }
}

resource "aws_lb_listener_rule" "gateway" {
  for_each = local.gateway_paths

  listener_arn = local.serving_listener_arn
  priority     = each.value.priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway.arn
  }

  condition {
    path_pattern {
      values = [each.value.pattern]
    }
  }
}
