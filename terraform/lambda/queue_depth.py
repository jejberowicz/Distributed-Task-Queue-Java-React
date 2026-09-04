"""Publica la profundidad de la cola de InferQueue como metrica de CloudWatch.

Es el reemplazo del scaler redis-streams de KEDA. KEDA hablaba Redis desde el
operador y le daba el numero al HPA; en ECS no hay nada equivalente, asi que el
numero hay que ponerlo en CloudWatch y dejar que Application Auto Scaling lo
lea desde ahi.

Mide XLEN, la misma decision que en la Fase 1 y por el mismo motivo: el worker
borra cada mensaje del stream despues de ackearlo (JobQueue.delete), asi que lo
que queda adentro es exactamente el trabajo pendiente mas el que esta en vuelo.
Los otros dos numeros que se podrian mirar no sirven aca:

  - el `lag` de XINFO GROUPS queda en NULL en cuanto se borran entradas del
    stream, y este worker borra en cada ack: se leeria 0 con la cola llena.
  - el largo del PEL sube cuando los workers estan ocupados, no cuando hay
    backlog, y escalar por eso es escalar al reves.

El protocolo de Redis se habla a mano en vez de traer redis-py. XLEN es un
comando de dos argumentos y una respuesta entera: son veinte lineas de socket
contra empaquetar una dependencia en un layer y versionarla. Si algun dia hace
falta algo mas que XLEN, la cuenta se da vuelta.
"""

import os
import socket

import boto3

REDIS_HOST = os.environ["REDIS_HOST"]
REDIS_PORT = int(os.environ.get("REDIS_PORT", "6379"))
STREAMS = [s for s in os.environ["STREAMS"].split(",") if s]
NAMESPACE = os.environ.get("METRIC_NAMESPACE", "InferQueue")
PROJECT = os.environ["PROJECT"]

TIMEOUT = 5

cloudwatch = boto3.client("cloudwatch")


def _encode(*args):
    """Serializa un comando en RESP: *N\\r\\n seguido de $len\\r\\nvalor\\r\\n."""
    out = [f"*{len(args)}\r\n".encode()]
    for arg in args:
        raw = arg.encode()
        out.append(b"$%d\r\n%s\r\n" % (len(raw), raw))
    return b"".join(out)


def _xlen(conn, reader, stream):
    conn.sendall(_encode("XLEN", stream))
    line = reader.readline()
    if not line:
        raise RuntimeError(f"Redis cerro la conexion al pedir XLEN {stream}")
    line = line.strip()
    if line.startswith(b":"):
        return int(line[1:])
    if line.startswith(b"-"):
        # Un stream que todavia no existe no es un error: es una cola vacia.
        # Redis igual devuelve 0 para XLEN sobre una clave inexistente, asi que
        # llegar aca significa otra cosa (WRONGTYPE, NOAUTH, ...).
        raise RuntimeError(f"Redis respondio {line.decode()} a XLEN {stream}")
    raise RuntimeError(f"Respuesta RESP inesperada: {line!r}")


def handler(event, context):
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=TIMEOUT) as conn:
        conn.settimeout(TIMEOUT)
        reader = conn.makefile("rb")
        depths = {stream: _xlen(conn, reader, stream) for stream in STREAMS}

    total = sum(depths.values())

    # La metrica sin la dimension Stream es la que mira la alarma: el backlog
    # total. Las que la llevan son para el grafico, para ver si lo que se acumula
    # es priority o standard.
    data = [
        {
            "MetricName": "QueueDepth",
            "Dimensions": [{"Name": "Project", "Value": PROJECT}],
            "Value": total,
            "Unit": "Count",
        }
    ]
    for stream, depth in depths.items():
        data.append(
            {
                "MetricName": "QueueDepth",
                "Dimensions": [
                    {"Name": "Project", "Value": PROJECT},
                    {"Name": "Stream", "Value": stream},
                ],
                "Value": depth,
                "Unit": "Count",
            }
        )

    cloudwatch.put_metric_data(Namespace=NAMESPACE, MetricData=data)

    print(f"QueueDepth total={total} {depths}")
    return {"total": total, "streams": depths}
