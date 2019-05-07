package main

import (
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"sync"
	"syscall"
	"time"
)

func checkHeartbeatErr(err error) {
	checkErr("heartbeat", err)
}

func checkLauncherErr(err error) {
	checkErr("launcher", err)
}

func checkErr(process string, err error) {
	if err != nil {
		fmt.Fprintf(os.Stderr, "(%v) %v\n", process, err.Error())
	}
}

// In the event the launch script is being intentionally terminated, this signal catcher routine
// ensures that the main program will stay alive and record the exit code of the script.
// Without it, there are possible race conditions in which the main program will terminate before
// the script, and thus not record the exit code to the result file
func signalCatcher(sigChan chan os.Signal) {
	for sig := range sigChan {
		fmt.Printf("(sig catcher) caught: %v\n", sig)
	}
}

func launcher(wg *sync.WaitGroup, doneChan chan bool,
	scriptPath string, logPath string, resultPath string, outputPath string) {

	defer wg.Done()
	recordExit := fmt.Sprintf("; echo $? > %v.tmp; mv %v.tmp %v; wait", resultPath, resultPath, resultPath)
	scriptWithExit := scriptPath + recordExit
	scriptCmd := exec.Command("/bin/sh", "-c", scriptWithExit)
	logFile, err := os.Create(logPath)
	checkLauncherErr(err)
	defer logFile.Close()

	if outputPath != "" {
		// capturing output
		outputFile, err := os.Create(outputPath)
		checkLauncherErr(err)
		defer outputFile.Close()
		scriptCmd.Stdout = outputFile
	} else {
		scriptCmd.Stdout = logFile
	}
	if outputPath != "" {
		// capturing output
		scriptCmd.Stderr = logFile
	} else {
		// Note: pointing to os.Stdout will not capture all err logs and fail unit tests
		scriptCmd.Stderr = scriptCmd.Stdout
	}
	// Prevents script from being terminated if program gets terminated
	scriptCmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	// Allows child processes of the script to be killed if kill signal sent to script's process group id
	scriptCmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	for i := 0; i < len(scriptCmd.Args); i++ {
		fmt.Printf("(launcher) args %v: %v\n", i, scriptCmd.Args[i])
	}
	scriptCmd.Start()
	fmt.Printf("(launcher) my pid (%v), launcher pid (%v)\n", os.Getpid(), scriptCmd.Process.Pid)
	// Note: If we do not call wait, the forked process will be zombied until the main program exits
	// ignoring SIGCHLD is not as portable as calling Wait()
	err = scriptCmd.Wait()
	checkLauncherErr(err)
	resultVal := scriptCmd.ProcessState.ExitCode()
	fmt.Printf("(launcher) script exit code: %v\n", resultVal)
	doneChan <- true
}

func heartbeat(wg *sync.WaitGroup, doneChan chan bool,
	controlDir string, resultPath string, logPath string) {

	defer wg.Done()
	_, err := os.Stat(controlDir)
	if os.IsNotExist(err) {
		fmt.Fprintf(os.Stderr, "%v\n", err.Error())
		return
	}
	_, err = os.Stat(resultPath)
	if !os.IsNotExist(err) {
		fmt.Printf("Result file already exists, stopping heartbeat.\n%v\n", resultPath)
		return
	}
	for {
		select {
		case <-doneChan:
			fmt.Println("(heartbeat) received script finished, exiting")
			return
		default:
			// heartbeat
			fmt.Println("(heartbeat) touch log")
			err = os.Chtimes(logPath, time.Now(), time.Now())
			checkHeartbeatErr(err)
			time.Sleep(time.Second * 3)
		}
	}
}

func main() {
	fmt.Printf("number of args: %v\n", os.Args)
	for i := 0; i < len(os.Args); i++ {
		fmt.Printf("arg %v: %v\n", i, os.Args[i])
	}
	argLen := len(os.Args)
	if argLen < 5 || argLen > 6 {
		// invalid number of args
		os.Stderr.WriteString("Input error: expected number of args is 5 or 6 (controlDir, resultPath, logPath, scriptPath, outputPath[opt])")
		return
	}
	controlDir := os.Args[1]
	resultPath := os.Args[2]
	logPath := os.Args[3]
	scriptPath := os.Args[4]
	outputPath := ""
	if argLen == 6 {
		outputPath = os.Args[5]
	}

	sigChan := make(chan os.Signal, 1)
	// Note: If signal.Ignore is used, this will be inherited by the script and it will be unable
	// to terminate in the STOP unit test of the BourneShellScriptTest suite. This is beause (under mac)
	// the script is terminated with SIGTERM instead of SIGKILL
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP, syscall.SIGQUIT)
	go signalCatcher(sigChan)

	doneChan := make(chan bool)
	var wg sync.WaitGroup
	wg.Add(2)
	go launcher(&wg, doneChan, scriptPath, logPath, resultPath, outputPath)
	go heartbeat(&wg, doneChan, controlDir, resultPath, logPath)
	wg.Wait()
	signal.Stop(sigChan)
	close(sigChan)
	fmt.Println("(main) done.")
}
