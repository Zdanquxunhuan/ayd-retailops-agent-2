"""Run the V1.9.2 business integration against an isolated JVM and two local H2 ledgers."""
import json
import os
import re
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from contextlib import contextmanager
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT / "target" / "repair192"
JAR = ROOT / "target/order-investigator-1.9.2.jar"
J17 = Path(os.getenv("LAB_JDK", "F:/ayd/jdk-17"))
J21 = Path(os.getenv("AGENT_JDK", "F:/ayd/order-project-jdk21/jdk-21.0.13+9"))
BUSINESS = Path(os.getenv("PUSH_MODULE", "F:/IDEAProject/b-push-center-prod/b-push-center-server"))
REPO = Path(os.getenv("PUSH_M2", "E:/Repositories"))
MVN = os.getenv("LAB_MVN", "E:/apache-maven-3.9.1/bin/mvn.cmd")
TOKEN = secrets.token_urlsafe(32)
OWNER = "demo-ops-a"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def compile_lab():
    OUT.mkdir(parents=True, exist_ok=True)
    result = subprocess.run([MVN, "-o", "-B", "-X", "-f", str(HERE / "pom.xml"),
        "-Dmaven.repo.local=" + str(REPO), "-Dmaven.main.skip=true",
        "org.apache.maven.plugins:maven-compiler-plugin:3.10.1:compile"],
        env=dict(os.environ, JAVA_HOME=str(J17)), capture_output=True, check=True)
    paths = re.search(rb"compilePath = \[(.*?)\]", result.stdout).group(1).decode().split(", ")
    with zipfile.ZipFile(JAR) as jar:
        h2, = [n for n in jar.namelist() if n.startswith("BOOT-INF/lib/h2-")]
        driver = OUT / "h2.jar"
        driver.write_bytes(jar.read(h2))
    cp = os.pathsep.join(map(str, [OUT, BUSINESS/"target/classes", driver, *paths[1:]]))
    lombok = REPO / "org/projectlombok/lombok/1.18.30/lombok-1.18.30.jar"
    service = BUSINESS / "src/main/java/cn/com/huoban168/b/push/center/service/pos/impl/MeituanServiceImpl.java"
    subprocess.run([str(J17/"bin/javac"), "--release","8","-encoding", "UTF-8", "-cp", cp+os.pathsep+str(lombok),
                    "-processorpath", str(lombok), "-d", str(OUT), str(service)], check=True)
    subprocess.run([str(J17/"bin/javac"),"-encoding","UTF-8","-cp",cp,"-d",str(OUT),str(HERE/"BusinessLab.java")], check=True)
    return cp


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


@contextmanager
def process(cmd, cwd, listen, name):
    env = {k:v for k,v in os.environ.items() if k.upper() not in
           {"DASHSCOPE_API_KEY","MT_APP_SECRET","MT_APP_ID","SPRING_PROFILES_ACTIVE","SPRING_APPLICATION_JSON"}}
    env["REPAIR_LAB_TOKEN"] = TOKEN
    with (OUT/(name+".log")).open("wb") as log:
        proc = subprocess.Popen(cmd, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(300):
                if proc.poll() is not None: raise RuntimeError(f"Startup failed: {name}.log")
                try:
                    with socket.create_connection(("127.0.0.1",listen),.2): break
                except OSError: time.sleep(.15)
            else: raise TimeoutError(name)
            yield
        finally:
            proc.terminate()
            proc.wait(timeout=20)


def request(port_number, path, token, body=None, status=200, method=None):
    req = urllib.request.Request(f"http://127.0.0.1:{port_number}"+path,
            data=None if body is None else json.dumps(body).encode(), method=method,
            headers={"Authorization":"Bearer "+token,"Content-Type":"application/json"})
    try: response = OPENER.open(req,timeout=30)
    except urllib.error.HTTPError as err: response=err
    with response:
        assert response.status == status,(path,status,response.status)
        return json.loads(response.read() or b"{}")


def check(cp, mode):
    a,b=port(),port()
    while a==b: b=port()
    command=[str(J21/"bin/java"),"-jar",str(JAR),"--spring.profiles.active=repair-lab",
             f"--server.port={a}",f"--repair.lab-port={b}"]
    with tempfile.TemporaryDirectory() as temp, process([str(J17/"bin/java"),"-cp",cp,"BusinessLab",
            str(b),str(a),mode],temp,b,mode+"-business"):
        counts=lambda:request(b,"/lab/counts",TOKEN)
        with process(command,temp,a,mode+"-agent"):
            case=request(a,"/cases",OWNER,{"orderId":"123456"},201)
            path=case["actions"]["requestRepair"]
            if mode=="PARTIAL":
                assert case["outcome"]=="POS_PARTIAL"
                request(a,path,OWNER,{},409)
                assert counts()=={"received":0,"pay":0,"sale":1}
                return counts()
            attempt=request(a,path,OWNER,{},201)
            confirm=path+"/"+attempt["id"]+"/confirm"
            target=attempt["target"]
            report=request(a,path.replace("repair-attempts","investigations"),OWNER,{"question":"确认补单"})
            assert report["status"]=="HANDOFF"
            request(a,confirm,"demo-ops-a-next",target,403)
            request(a,confirm,OWNER,dict(target,orderId="123457"),409)
            internal="/lab/repairs/"+attempt["id"]
            request(b,internal,OWNER,status=403,method="POST")
            request(b,internal,TOKEN,status=403,method="POST")
            assert counts()=={"received":0,"pay":0,"sale":0}
            if mode=="ARRIVED_BEFORE_CONFIRM":
                (Path(temp)/"delivered").touch()
                invalid=request(a,confirm,OWNER,target,409)
                assert invalid["reason"]=="POS_PRESENT"
                assert counts()=={"received":0,"pay":1,"sale":1}
                return counts()
            record=request(a,confirm,OWNER,target)
            verified=mode!="MISSING_MAPPING"
            assert record["status"]==("VERIFIED" if verified else "NEEDS_VERIFICATION"),record["execution"]
            assert record["execution"]["callResult"]==("UNKNOWN" if mode=="LOST_REPLY" else "ACKNOWLEDGED")
            assert request(a,confirm,OWNER,target)["status"]==record["status"]
        with process(command,temp,a,mode+"-restart"):
            assert request(a,confirm,OWNER,target)["status"]==record["status"]
            assert request(a,confirm.replace("/confirm","/verify"),OWNER,{})["status"]==record["status"]
        result=counts()
        assert result=={"received":1,"pay":int(verified),"sale":2*int(verified)},result
        return result


if __name__=="__main__":
    classpath=compile_lab()
    for scenario in ["NORMAL","LOST_REPLY","MISSING_MAPPING","PARTIAL","ARRIVED_BEFORE_CONFIRM"]:
        print(scenario,check(classpath,scenario),flush=True)

