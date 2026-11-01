"""``sit_assesspy.sit_assess_lib.SitAssessLib`` from soton_corenlppy 1.0.0.

The 2017 call is ``SitAssessLib(config, logger)``. Release 1.0.0 takes
``(config, useLogger=True, sitAssessLogger=None)``, so a logger passed in
the second position is treated as a true flag and the library builds its
own logger. Table creation still uses the config dict.
"""

from soton_corenlppy.sit_assess.sit_assess_lib import SitAssessLib  # noqa: F401

NOT_THE_0_1_0_WHEEL = True
