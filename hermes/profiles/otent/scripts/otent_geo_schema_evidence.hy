(import otent_vision_evidence [main])
(import sys)
(setv (cut sys.argv 1 None) ["schema"])
(raise (SystemExit (main)))
