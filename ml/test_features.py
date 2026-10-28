import pytest

from features import FEATURE_NAMES, features, load_brands, registrable_domain

BRANDS = load_brands()


def f(url):
    return dict(zip(FEATURE_NAMES, features(url, BRANDS)))


def test_feature_count_matches_names():
    assert len(features("https://example.com/", BRANDS)) == len(FEATURE_NAMES) == 19


def test_free_host():
    assert f("https://acaparis.godaddysites.com/")["free_host"] == 1
    assert f("https://selvaker.cloudaccess.host/index.html")["free_host"] == 1
    assert f("https://sites.google.com/view/kyc-help")["free_host"] == 1
    assert f("https://www.godaddy.com/")["free_host"] == 0
    assert f("https://www.google.com/")["free_host"] == 0


def test_ipv4_host():
    x = f("http://203.0.113.10/verify/account")
    assert x["has_ip_host"] == 1 and x["subdomain_depth"] == 0 and x["brand_min_lev"] == 1.0


def test_ipv6_host():
    assert f("http://[2001:db8::1]/login")["has_ip_host"] == 1


def test_userinfo_trick_uses_real_host():
    x = f("https://examplebank.com@login-check.example/")
    assert x["has_at"] == 1
    assert registrable_domain("https://examplebank.com@login-check.example/") == "login-check.example"


def test_at_in_path_is_not_userinfo():
    assert f("https://example.com/user@mail")["has_at"] == 0


def test_multi_part_suffix_co_in():
    assert registrable_domain("https://www.sbi.co.in/web/personal") == "sbi.co.in"
    assert f("https://www.sbi.co.in/")["subdomain_depth"] == 1


def test_multi_part_suffix_edu_au():
    assert registrable_domain("https://www.rmit.edu.au/") == "rmit.edu.au"


def test_deep_nesting():
    x = f("https://login.secure.examplebank.com.attacker.example/")
    assert x["subdomain_depth"] == 4 and x["dot_count"] == 5


def test_missing_scheme_still_parses():
    assert registrable_domain("bank.example/login") == "bank.example"
    assert f("bank.example/login")["is_https"] == 0


def test_punycode_flags_tld_risk():
    assert f("https://xn--pypal-4ve.com/")["tld_risk"] == 1


def test_risky_tld():
    assert f("https://free-reward.xyz/")["tld_risk"] == 1
    assert f("https://example.com/")["tld_risk"] == 0


def test_shortener():
    assert f("https://bit.ly/example")["is_shortener"] == 1


def test_official_brand_is_not_brand_in_subdomain():
    x = f("https://www.hdfcbank.com/")
    assert x["brand_in_subdomain"] == 0


def test_brand_in_lookalike_host():
    x = f("https://hdfc-kyc-update.example/login")
    assert x["brand_in_subdomain"] == 1 and x["susp_words"] >= 2 and x["hyphen_count_host"] == 2


def test_typosquat_close_to_brand():
    # rn -> m homoglyph: "paytrn" de-glyphs to "paytm"
    assert f("https://paytrn.example/")["brand_min_lev"] == 0.0
    assert f("https://wikipedia.org/")["brand_min_lev"] > 0.5


def test_query_params_and_percent_decoding():
    x = f("https://example.com/%6Cogin?a=1&b=2&c=")
    assert x["num_params"] == 3 and x["susp_words"] == 1


def test_very_long_url_is_capped():
    x = f("https://example.com/" + "a" * 10000)
    assert x["url_len"] == 2048


@pytest.mark.parametrize("bad", ["", "   ", "http://", "http://[::1"])
def test_garbage_does_not_crash(bad):
    assert len(features(bad, BRANDS)) == 19
