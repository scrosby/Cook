import sys
import time
import urllib
from concurrent.futures.thread import ThreadPoolExecutor
from concurrent.futures import TimeoutError

import datetime
from urllib.parse import parse_qs

class Query(object):
    def factory(rawline):
        """Returns None if the query isn't a list query. Throws an exception if the query line is not complete or correctly parsed."""
        fields = rawline.split(" ")
        #print(fields)

        path = fields[6];
        if not path.startswith("/list"):
            return None

        out = Query()
        out.rawpath = path
        out.querytime = datetime.datetime.strptime(fields[3],"[%d/%b/%Y:%H:%M:%S")
        out.queryDict = parse_qs(path.split("?")[1])
        out.castToLong("start-ms")
        out.castToLong("end-ms")
        return out

    def castToLong(self,key):
        if (key in self.queryDict):
            self.queryDict[key] = int(self.queryDict[key][0])

    def __init__(self):
        pass

    def scaleToNow(self):
        out = Query()
        out.querytime = self.querytime
        out.queryDict = self.queryDict.copy()
        now = int(datetime.datetime.utcnow().timestamp())*1000
        newEnd = max(now,out.queryDict["end-ms"])
        offset = newEnd-out.queryDict["end-ms"]
        newStart = offset+out.queryDict["start-ms"]
        out.queryDict["end-ms"] = newEnd
        out.queryDict["start-ms"] = newStart
        out.rawpath = self.rawpath
        return out

    def make(self):
        return "/list?" + urllib.parse.urlencode(self.scaleToNow().queryDict,doseq=True)


MAX_AGE = 150*60*60 # The oldest query we'll rerun
MAX_RUNTIME = 120 # How long to run the queries for?
MAX_WORKERS = 4

shutting_down = False
got = 0
sent = 0



executor = ThreadPoolExecutor(max_workers=MAX_WORKERS)

def doOne(prefix,request):
    global sent,got,shutting_down
    if shutting_down:
        return
    url = prefix+request.make();
    print("Trying to get1: "+url)
    sent = sent + 1
    #r = requests.get(url)
    #r.json()
    #print("Got "+repr(len(r))+ " from "+url);
    got = got + 1
    time.sleep(5)

def runAndWait(prefix, requests):
    global shutting_down
    now = datetime.datetime.utcnow()
    print("Running "+repr(len(requests))+ " requests")
    futures = [executor.submit(doOne,prefix,ii) for ii in requests]

    while True:
        for ii in futures:
            try:
                ii.result(2)
            except TimeoutError:
                pass
            time.sleep(2)
            if datetime.datetime.utcnow() - now > datetime.timedelta(seconds=MAX_RUNTIME):
                shutting_down = True
                print ("Passed our timeout")
                [ii.cancel for ii in futures]
                executor.shutdown(True)
                print ("Cancellation done. Sent "+repr(sent)+" got "+repr(got)+ " of "+repr(len(requests)))
                sys.exit(0)

def makeQueriesFromStream(stream):
    threshold = datetime.datetime.utcnow()-datetime.timedelta(seconds=MAX_AGE)
    requests = [Query.factory(ii) for ii in stream if ii is not None]
    print("Found "+repr(len(requests))+ " requests in input")
    requests = [ii for ii in requests if ii is not None]
    print("Found "+repr(len(requests))+ " requests that are /list")
    requests = [ii for ii in requests if ii.querytime > threshold]
    print("Kept "+repr(len(requests))+ " requests at least "+repr(MAX_AGE)+" in age")
    return requests

#while true ; do tail -c 10000000 $(ls -alsrt access_log* | tail -1) | grep /list | tail -1000 ;

runAndWait(sys.argv[1],makeQueriesFromStream(sys.stdin))

