# Rating kick (Oracle server)

GitHub sometimes does not fire scheduled workflows. This systemd timer on an Oracle server asks
GitHub to run `.github/workflows/rating.yml` every 6 hours (01:41, 07:41, 13:41, 19:41 UTC).

Install (as root):

    install -m 755 jc-rating-kick.sh /usr/local/bin/
    install -m 644 jc-rating-kick.service jc-rating-kick.timer /etc/systemd/system/
    printf 'GH_TOKEN=%s\n' "<token>" > /etc/jc-rating-kick.env && chmod 600 /etc/jc-rating-kick.env
    systemctl daemon-reload && systemctl enable --now jc-rating-kick.timer

Token: GitHub → Settings → Developer settings → Fine-grained tokens; repository access: only
JacCollector; permissions: Actions — read and write (nothing else). It can only start workflows.

Logs: `journalctl -u jc-rating-kick`.
