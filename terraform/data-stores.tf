# Postgres y Redis dejan de ser StatefulSets y pasan a ser servicios
# gestionados. Es el cambio más grande respecto de la Fase 1 y el más fácil de
# justificar: nadie quiere ser el DBA de su propio demo.

# --- Credenciales ------------------------------------------------------------
#
# Mismo principio que el Secret de Kubernetes: la contraseña nunca aparece en la
# definición de la tarea, sólo se referencia. En ECS eso se hace con el bloque
# `secrets` del container definition, que resuelve el valor recién al arrancar
# la tarea y no lo deja en la task definition —que es pública para cualquiera
# con `ecs:DescribeTaskDefinition`.
#
# Parameter Store y no Secrets Manager: SecureString es gratis, un secreto de
# Secrets Manager cuesta 0.40 USD/mes. Lo que se pierde es la rotación
# automática, que en una demo no se usa.

resource "random_password" "db" {
  length = 32
  # RDS rechaza '/', '@', '"' y el espacio en la contraseña del master.
  override_special = "!#%&*()-_=+[]{}<>:?"
}

resource "random_password" "admin_token" {
  length  = 40
  special = false
}

resource "aws_ssm_parameter" "db_password" {
  name  = "/${local.name}/db_password"
  type  = "SecureString"
  value = random_password.db.result
}

resource "aws_ssm_parameter" "admin_token" {
  name  = "/${local.name}/admin_token"
  type  = "SecureString"
  value = random_password.admin_token.result
}

# --- Postgres ----------------------------------------------------------------

resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "main" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  db_name  = "inferqueue"
  username = "inferqueue"
  password = random_password.db.result

  # gp2 y no gp3: el free tier cubre 20 GB de "General Purpose (SSD)", que es gp2.
  allocated_storage = var.db_allocated_storage
  storage_type      = "gp2"
  storage_encrypted = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false

  # Sin multi-AZ ni backups: las dos cosas duplican el costo y esto es una demo
  # cuyos datos son jobs de prueba. En cualquier otra cosa, invertir ambas.
  multi_az                = false
  backup_retention_period = 0

  # Las tres banderas que hacen que `terraform destroy` termine y no pida un
  # nombre de snapshot ni se plante en la protección de borrado. En producción
  # son exactamente al revés, y ese es el punto: son propiedades del entorno,
  # no del código.
  skip_final_snapshot = true
  deletion_protection = false
  apply_immediately   = true

  auto_minor_version_upgrade   = true
  performance_insights_enabled = false
}

# --- Redis -------------------------------------------------------------------

resource "aws_elasticache_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

# El parameter group existe sólo por una línea, y esa línea importa mucho.
#
# El default de ElastiCache es maxmemory-policy = volatile-lru: cuando la
# memoria se llena, Redis empieza a desalojar claves con TTL. En un cache eso
# es lo correcto. Acá adentro no hay un cache: hay una cola, un PEL con los
# jobs en vuelo y un ZSET de reintentos. Desalojar cualquiera de esos tres es
# perder trabajo en silencio, sin un error en ningún lado.
#
# noeviction hace que Redis conteste con error en vez de tirar datos, y el
# error sí se ve.
resource "aws_elasticache_parameter_group" "main" {
  name   = local.name
  family = "redis7"

  parameter {
    name  = "maxmemory-policy"
    value = "noeviction"
  }
}

resource "aws_elasticache_cluster" "main" {
  cluster_id           = local.name
  engine               = "redis"
  engine_version       = var.redis_engine_version
  node_type            = var.redis_node_type
  num_cache_nodes      = 1
  parameter_group_name = aws_elasticache_parameter_group.main.name
  port                 = 6379

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.redis.id]

  # Un nodo suelto y no un replication group: la réplica no entra en el free
  # tier. Consecuencia concreta, y conviene tenerla clara antes de que pase: no
  # hay AOF como el `--appendonly yes` del compose, así que si el nodo se
  # reinicia se pierde el contenido de los streams. Los jobs siguen en Postgres,
  # pero los que estaban RUNNING quedan sin mensaje en el PEL y el
  # PendingReclaimer no tiene qué reclamar: hay que reencolarlos a mano.
  snapshot_retention_limit = 0

  apply_immediately = true
}
