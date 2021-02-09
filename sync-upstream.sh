# before syncing you need to add upstream once using this command:
# git remote add upstream https://github.com/litan/kojojs-editor.git
echo *** syncing with remote upstream
git remote -v
git fetch upstream
git checkout master
git merge upstream/master
