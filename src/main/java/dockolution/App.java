package dockolution;

import models.GithubRepository;
import models.SQL.Diff;
import models.SQL.Dockerfile;
import models.SQL.Snapshot;
import org.apache.commons.lang.ArrayUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import process.extract.DateExtractor;
import services.GitHubMinerService;
import services.HibernateService;
import tools.dockerparser.DockerParser;
import tools.githubminer.DiffProcessor;
import tools.githubminer.GitCloner;
import tools.githubminer.GitHistoryFilter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Created by salizumberi-laptop on 29.12.2016.
 */
public class App {

    public final static String GITAPI = "https://api.github.com/";
    public final static String REPOS = "repos/";

    public static void main(String[] args) throws Exception {

      //  String repoName = args[0];
       // String dockerPath = args[1];

        String repoName = "probr/probr-core";
        String dockerPath = "Dockerfile";

        GithubRepository metaData = GitHubMinerService.getGitHubRepository(GITAPI + REPOS + repoName);

        String repoFolderName = REPOS + String.valueOf(metaData.id);
        String repoFolderNameDotGit = repoFolderName + "\\.git";

        GitCloner gitCloner = new GitCloner();
        System.out.println("2. Clone Repo");
        gitCloner.cloneRepository(metaData.git_url, repoFolderName);

        GitHistoryFilter gitHistoryFilter = new GitHistoryFilter();
        Repository repository = gitHistoryFilter.getRepository(repoFolderNameDotGit);

        System.out.println("4. Load History of Repo");

        Git git = new Git(repository);

        RevWalk reVwalk = new RevWalk(repository);
        List<RevCommit> historyOfFile = gitHistoryFilter.getDockerHistory(repoFolderNameDotGit, dockerPath);

        Dockerfile dockerfile = new Dockerfile();
        dockerfile.setLocalRepoPath(repoFolderName);
        dockerfile.setRepo_id(metaData.id);
        dockerfile.setDockerPath(dockerPath);
        Date created_at = DateExtractor.getDateFromJsonString(metaData.created_at);
        long unixTime = (long) created_at.getTime()/1000;
        dockerfile.setFirstCommitDate(Long.valueOf(unixTime));
        dockerfile.setForks(metaData.forks_count);
        dockerfile.setSize(metaData.size);
        dockerfile.setFork(metaData.fork);
        dockerfile.setNetworkCount(metaData.network_count);
        dockerfile.setRepoPath(repoName);
        dockerfile.setStargazers(metaData.stargazers_count);
        dockerfile.setOpneIssues(metaData.open_issues);
        dockerfile.setOwnerType(metaData.owner.type);
        dockerfile.setWatchers(metaData.watchers_count);
        dockerfile.setSubscribers(metaData.subscribers_count);
        dockerfile.setFirstDockerCommitDate(DateExtractor.getUnixDateFromCommit(historyOfFile.get(historyOfFile.size() - 1)));
        dockerfile.setCommits(historyOfFile.size());
        dockerfile.setViolatedRules(dockerfile.getViolatedRules());

        int index = 0;
        System.out.println("6. Create Snapshots for the Dockerfile");
        Collections.reverse(historyOfFile);

        for (RevCommit commit : historyOfFile) {
            Snapshot snapshot = getDockerfileFromCommit(commit, repository, dockerPath, repoFolderName);
            if (index == 0) {
                dockerfile.addSnapshots(snapshot,
                        true,
                        index,
                        metaData.id,
                        commit,
                        repository,git);
            } else {
                dockerfile.addSnapshots(snapshot,
                        false,
                        index,
                        metaData.id,
                        commit,
                        repository, git);
            }
            index++;
        }

        System.out.println("7. Set Dates for the Snapshots");

        for (int i = 0; i < dockerfile.getDockerfileSnapshots().size(); i++) {
            Snapshot snapshot = dockerfile.getDockerfileSnapshots().get(i);
            if (i == 0) {
                long nowUnx = new Date().getTime() / 1000;
                dockerfile.getDockerfileSnapshots().get(i).setFromDate(snapshot.getCommitDate());
                dockerfile.getDockerfileSnapshots().get(i).setToDate(nowUnx);
            } else {
                Snapshot olderSnapshot = dockerfile.getDockerfileSnapshots().get(i - 1);
                dockerfile.getDockerfileSnapshots().get(i).setFromDate(snapshot.getCommitDate());
                dockerfile.getDockerfileSnapshots().get(i).setToDate(olderSnapshot.getCommitDate());
            }
        }

            for (int i = 0; i < dockerfile.getDockerfileSnapshots().size(); i++) {
                Diff prevDiff = null;
                if (i == 0) {
                    prevDiff = DiffProcessor.getDiff(null, dockerfile.getDockerfileSnapshots().get(0));
                } else {
                    prevDiff = DiffProcessor.getDiff(dockerfile.getDockerfileSnapshots().get(i - 1), dockerfile.getDockerfileSnapshots().get(i));
                }
                dockerfile.getDockerfileSnapshots().get(i).setOldDiff(prevDiff);
            }

        for (int i = 0; i < dockerfile.getDockerfileSnapshots().size()-1; i++) {
            Diff nextDiff = null;
            if (dockerfile.getDockerfileSnapshots().size() == 1) {
            }else {
                nextDiff = dockerfile.getDockerfileSnapshots().get(i + 1).getDiffs().get(0);
                dockerfile.getDockerfileSnapshots().get(i).setNewDiff(nextDiff);
            }
        }

        System.out.println("## HIBERNATESERVICE: INSERT Dockerfile Object");
        HibernateService.createDockerfile(dockerfile);

        //JsonElement json = JsonPrinter.getJsonObject(dockerFile, String.valueOf(dockerFile.repo_id));
        //System.out.println(JsonPrinter.getJsonString(json));
        //getDockerfileFromCommit(historyOfFile.get(1),repository,dockerPath,repoFolderName);
        System.out.println("1##################################################################################################");
        //   showDiffFilesOfHistory(historyOfFile, repository,git);
        System.out.println("2##################################################################################################");
        // DiffProcessor.showDiffbetweenTwoFiles(historyOfFile.get(1), historyOfFile.get(0), dockerPath, repository, git);
        //    CommitProcessor.getChangedFilesWithinCommit(historyOfFile.get(0), repository);
        repository.close();
        reVwalk.close();
        git.close();
        git.getRepository().close();
    }

    public static Snapshot getDockerfileFromCommit(RevCommit revCommit, Repository repository, String dockerPath, String localPath) throws IOException {
        // a RevWalk allows to walk over commits based on some filtering that is defined
        System.out.println("6.1 Find File of Commit (getDockerfileFromCommit)");
        Snapshot snapshot = null;
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(revCommit.getId());
            // and using commit's tree find the path
            RevTree tree = commit.getTree();
            File tempDockferile = null;
            String tempname = null;
            String filename = null;
            // now try to find a specific file
            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                treeWalk.setFilter(PathFilter.create(dockerPath));
                if (!treeWalk.next()) {
                    // return snapshot = getDockerFile(localPath, dockerPath, tempname);
                    throw new IllegalStateException("Did not find expected file 'Dockerfile'");
                }
                ObjectId objectId = treeWalk.getObjectId(0);
                ObjectLoader loader = repository.open(objectId);

                // and then one can the loader to read the file
                Random ran = new Random();
                filename = "tempDock";
                tempname = localPath + "/" + filename;
                tempDockferile = new File(tempname);
                FileOutputStream fop = new FileOutputStream(tempDockferile);
                loader.copyTo(fop);
                fop.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            revWalk.dispose();
            System.out.println("6.2 Create Snapshot from File (getDockerfileFromCommit)");
            snapshot = getDockerFile(localPath, dockerPath, tempname);
            if (tempDockferile.exists()) {
                tempDockferile.delete();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return snapshot;
    }

    public static Snapshot getDockerFile(String localPath, String dockerPath, String filename) throws IOException {
        DockerParser dockerParser = new DockerParser(localPath, dockerPath);
        Snapshot dockerfileSnapshot;
        if (filename == null) {
            dockerfileSnapshot = dockerParser.getDockerfileObject();
        } else {
            dockerfileSnapshot = dockerParser.getDockerfileObject(filename);
        }

        // System.out.println(dockerParser.toString());
        return dockerfileSnapshot;
    }

}
