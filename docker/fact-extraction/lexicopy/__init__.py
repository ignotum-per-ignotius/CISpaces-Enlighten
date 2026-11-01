"""Import alias for the 2017 name ``lexicopy``.

``fact_extraction_app.py`` imports this package and does not call it.
The lexicon used at runtime is the RabbitMQ vocabulary message.
This is not ``lexicopy-0.1.0-py27-none-any``. It re-exports
``soton_corenlppy.lexico`` from release 1.0.0.
"""

from soton_corenlppy import lexico as lexicon

NOT_THE_0_1_0_WHEEL = True
UPSTREAM = "soton_corenlppy.lexico==1.0.0"
