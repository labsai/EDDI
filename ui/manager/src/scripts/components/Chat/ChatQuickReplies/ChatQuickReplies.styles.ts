import { makeStyles } from '@material-ui/core/styles';

const useStyles = makeStyles({
  quickReplies: {
    display: 'flex',
    flexDirection: 'row',
    flexWrap: 'wrap',
    margin: '10px 0',
  },
  quickRepliesButton: {
    marginRight: '10px',
    marginBottom: '5px',
    borderRadius: '10px',
  },
  hidden: {
    display: 'none',
  },
});

export default useStyles;
