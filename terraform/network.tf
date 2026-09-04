# Red.
#
# La decisión que define todo lo demás: no hay NAT Gateway.
#
# El libro dice tareas en subredes privadas saliendo por un NAT. Un NAT Gateway
# cuesta ~32 USD/mes más el tráfico, y es de lejos el ítem más caro de este
# stack — más que la base, el Redis y el ALB juntos. Así que las tareas de
# Fargate van en subredes públicas con IP pública y salen por el Internet
# Gateway, que es gratis.
#
# Lo que se pierde con eso es menos de lo que parece: la IP pública no abre
# nada, porque quien decide qué entra es el security group y el de las tareas
# sólo acepta tráfico del ALB. Lo que sí se pierde es la defensa en profundidad
# —una regla mal escrita expone la tarea a internet, contra un NAT donde no hay
# ruta de entrada posible— y por eso los datos no juegan a esto: RDS y
# ElastiCache viven en subredes privadas sin ruta a internet, y ahí sí no hay
# forma de alcanzarlos desde afuera.

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = local.name }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = local.name }
}

resource "aws_subnet" "public" {
  count = length(local.azs)

  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = true

  tags = { Name = "${local.name}-public-${local.azs[count.index]}" }
}

resource "aws_subnet" "private" {
  count = length(local.azs)

  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 10)
  availability_zone = local.azs[count.index]

  tags = { Name = "${local.name}-private-${local.azs[count.index]}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name}-public" }
}

resource "aws_route_table_association" "public" {
  count          = length(aws_subnet.public)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# Sin ruta 0.0.0.0/0: lo que vive acá adentro no habla con internet ni internet
# con ello. La única salida es dentro de la VPC.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name}-private" }
}

resource "aws_route_table_association" "private" {
  count          = length(aws_subnet.private)
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# El publicador de métricas es una Lambda dentro de la VPC, y una Lambda en VPC
# nunca recibe IP pública: el truco de las subredes públicas no le sirve, no
# tiene forma de salir por el Internet Gateway. Pero necesita llamar a
# PutMetricData.
#
# Un endpoint de interfaz mete la API de CloudWatch adentro de la VPC como una
# ENI privada. Cuesta ~7 USD/mes, cuatro veces menos que el NAT que evitaría.
# (Los logs de la Lambda no pasan por acá: esa entrega la hace el servicio de
# Lambda por fuera de la ENI del cliente.)
resource "aws_vpc_endpoint" "monitoring" {
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.region}.monitoring"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.vpce.id]
  private_dns_enabled = true

  tags = { Name = "${local.name}-monitoring" }
}

# --- Security groups ---------------------------------------------------------
#
# Acá está el perímetro real. Cada regla nombra al security group de origen en
# vez de un rango de IPs: así la regla sigue siendo correcta cuando Fargate
# recicla las tareas y les cambia la IP en cada deploy.

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "Entrada publica"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  count = length(var.allowed_ingress_cidrs)

  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = var.allowed_ingress_cidrs[count.index]
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  count = var.certificate_arn == "" ? 0 : length(var.allowed_ingress_cidrs)

  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = var.allowed_ingress_cidrs[count.index]
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "alb_all" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "tasks" {
  name        = "${local.name}-tasks"
  description = "Tareas de ECS"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-tasks" }
}

# Sólo el ALB entra. Es esto —y no la ausencia de IP pública— lo que mantiene
# cerradas las tareas.
resource "aws_vpc_security_group_ingress_rule" "tasks_from_alb_gateway" {
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "tasks_from_alb_dashboard" {
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 80
  to_port                      = 80
  ip_protocol                  = "tcp"
}

# Salida abierta: hace falta para bajar las imágenes de ECR, leer los parámetros
# de SSM y publicar los logs.
resource "aws_vpc_security_group_egress_rule" "tasks_all" {
  security_group_id = aws_security_group.tasks.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "rds" {
  name        = "${local.name}-rds"
  description = "Postgres"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-rds" }
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_tasks" {
  security_group_id            = aws_security_group.rds.id
  referenced_security_group_id = aws_security_group.tasks.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_security_group" "redis" {
  name        = "${local.name}-redis"
  description = "ElastiCache"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-redis" }
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_tasks" {
  security_group_id            = aws_security_group.redis.id
  referenced_security_group_id = aws_security_group.tasks.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}

# El publicador de métricas hace XLEN, así que también necesita entrar.
resource "aws_vpc_security_group_ingress_rule" "redis_from_lambda" {
  security_group_id            = aws_security_group.redis.id
  referenced_security_group_id = aws_security_group.lambda.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}

resource "aws_security_group" "lambda" {
  name        = "${local.name}-lambda"
  description = "Publicador de la profundidad de cola"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-lambda" }
}

resource "aws_vpc_security_group_egress_rule" "lambda_all" {
  security_group_id = aws_security_group.lambda.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "vpce" {
  name        = "${local.name}-vpce"
  description = "Endpoint de interfaz de CloudWatch"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-vpce" }
}

resource "aws_vpc_security_group_ingress_rule" "vpce_from_lambda" {
  security_group_id            = aws_security_group.vpce.id
  referenced_security_group_id = aws_security_group.lambda.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
}
