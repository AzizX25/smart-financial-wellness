from __future__ import annotations

CATALOG_VERSION = "merchant-catalog-v1"
TAXONOMY_VERSION = "system-taxonomy-v2"
FEATURE_VERSION = "merchant-recognition-features-v1"
SEED = 20260819

# Curated aliases are intentionally unambiguous and contain no transaction/user data.
MERCHANT_CATALOG: dict[str, tuple[str, tuple[str, ...]]] = {
    "MONOPRIX": ("FOOD", ("monoprix", "monoprix tunisie")),
    "CARREFOUR": ("FOOD", ("carrefour", "carrefour market")),
    "MAGASIN_GENERAL": ("FOOD", ("magasin general", "mg tunisie")),
    "GEANT": ("FOOD", ("geant", "geant tunisie")),
    "AZIZA": ("FOOD", ("aziza", "aziza market")),
    "STEG": ("BILLS_SUBSCRIPTIONS", ("steg", "societe tunisienne electricite gaz")),
    "SONEDE": ("BILLS_SUBSCRIPTIONS", ("sonede", "societe nationale eaux")),
    "TUNISIE_TELECOM": ("BILLS_SUBSCRIPTIONS", ("tunisie telecom", "telecom tunisie")),
    "OOREDOO": ("BILLS_SUBSCRIPTIONS", ("ooredoo", "ooredoo tunisie")),
    "ORANGE_TUNISIE": ("BILLS_SUBSCRIPTIONS", ("orange tunisie", "orange tn")),
    "TOPNET": ("BILLS_SUBSCRIPTIONS", ("topnet", "topnet internet")),
    "NETFLIX": ("BILLS_SUBSCRIPTIONS", ("netflix", "netflix com")),
    "SPOTIFY": ("BILLS_SUBSCRIPTIONS", ("spotify", "spotify premium")),
    "BOLT": ("TRANSPORT", ("bolt", "bolt ride")),
    "YASSIR": ("TRANSPORT", ("yassir", "yassir tunisie")),
    "SNCFT": ("TRANSPORT", ("sncft", "train sncft")),
    "PHARMACIE_CENTRALE": ("HEALTH", ("pharmacie centrale", "pharmacie centrale tunis")),
    "PHARMACIE_EL_AMEN": ("HEALTH", ("pharmacie el amen", "pharmacie amen")),
    "JUMIA": ("SHOPPING", ("jumia", "jumia tunisie")),
    "ZARA": ("SHOPPING", ("zara", "zara tunisia")),
    "HM": ("SHOPPING", ("h and m", "hm store")),
    "LC_WAIKIKI": ("SHOPPING", ("lc waikiki", "lcw tunisie")),
    "MYTEK": ("SHOPPING", ("mytek", "mytek informatique")),
    "TUNISIANET": ("SHOPPING", ("tunisianet", "tunisianet informatique")),
    "PATHE": ("LEISURE", ("pathe", "pathe tunis city")),
    "STEAM": ("LEISURE", ("steam", "steam games")),
    "COURSERA": ("EDUCATION", ("coursera", "coursera course")),
    "UDEMY": ("EDUCATION", ("udemy", "udemy course")),
    "ACADEMIE_CARTHAGE": ("EDUCATION", ("academie carthage", "formation carthage")),
}

UNKNOWN_TEXTS = (
    "retrait dab", "atm banque demo retrait", "virement interne", "cheque numero reference",
    "operation guichet", "paiement divers", "service local inconnu", "achat comptant",
    "transaction carte", "prelevement organisme", "fournisseur sans nom", "commerce quartier",
)

