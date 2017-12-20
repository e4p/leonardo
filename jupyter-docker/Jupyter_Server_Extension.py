from notebook.utils import url_path_join
from notebook.base.handlers import IPythonHandler
import subprocess
import os
import pipes
import json
import tornado.gen
import tornado.web.HTTPError
from notebook.base.handlers import IPythonHandler

def sanitize(pathstr):
    """Expands to absolute paths, makes intermediate dirs, and quotes to remove any shell naughtiness."""
    #expanduser behaves fine with gs:// urls, thankfully
    expanded = os.path.expanduser(pathstr)
    if not pathstr.startswith("gs://"):
        try:
            os.makedirs(expanded)
        except OSError: #thrown if dirs already exist
            pass
    return pipes.quote(expanded)

@gen.coroutine
def localize(pathdict):
    """treats the given dict as a string/string map and sends it to gsutil"""
    with open("localization.log", 'a') as locout:
        for key in pathdict:
            cmd = " ".join(["gsutil -m -q cp -R", sanitize(key), sanitize(pathdict[key])])
            subprocess.call(cmd, stderr=locout, shell=True)

class LocalizeHandler(IPythonHandler):
    def post(self):
        jbody = self.request.body.decode('utf-8')
        try:
            pathdict = json.loads(jbody)
        except json.decoder.JSONDecodeError:
            raise HTTPError(400, "Body must be JSON object of type string/string")

        if type(pathdict) is not dict:
            raise HTTPError(400, "Body must be JSON object of type string/string")

        if not all(map(lambda v: type(v) is str, pathdict.values()):
            raise HTTPError(400, "Body must be JSON object of type string/string")

        #complete the request HERE, without waiting for the localize to run
        self.finish()

        #do the localize in a coroutine after the request is finished. we might not need the yield statement
        yield localize(pathdict)

# class LocalisationHandler(IPythonHandler):
#     def post(self):
#         self.set_status(200)
#         self.finish()
#
# class DelocalisationHandler(IPythonHandler):
#     def post(self):
#         self.set_status(200)
#         self.finish()


def load_jupyter_server_extension(nb_server_app):
    """
    Called when the extension is loaded.

    Args:
        nb_server_app (NotebookWebApplication): handle to the Notebook webserver instance.
    """
    web_app = nb_server_app.web_app
    host_pattern = '.*$'
    route_pattern = url_path_join(web_app.settings['base_url'], '/api/localise')
    web_app.add_handlers(host_pattern, [(route_pattern, LocalizeHandler)])
    # route_pattern = url_path_join(web_app.settings['base_url'], '/api/delocalise')
    # web_app.add_handlers(host_pattern, [(route_pattern, DelocalisationHandler)])
