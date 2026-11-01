"""``openiepy.comp_sem_lib`` as called by fact_extraction_app.py.

Re-export of ``soton_corenlppy.re.comp_sem_lib`` from release 1.0.0.
``get_seed_tuples_from_extractions`` is not in that release. The request
path uses ``stanford_engine.seed_tuples_from_extractions`` and labels it
as a reconstruction.
"""

from soton_corenlppy.re.comp_sem_lib import *  # noqa: F401,F403
from soton_corenlppy.re import comp_sem_lib as _upstream

NOT_THE_0_1_0_WHEEL = True
UPSTREAM_MODULE = _upstream.__name__
