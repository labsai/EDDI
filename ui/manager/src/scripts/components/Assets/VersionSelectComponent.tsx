import FormControl from '@material-ui/core/FormControl';
import MenuItem from '@material-ui/core/MenuItem';
import OutlinedInput from '@material-ui/core/OutlinedInput';
import Select from '@material-ui/core/Select';
import { makeStyles } from '@material-ui/core/styles';
import * as _ from 'lodash';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import Parser from '../utils/Parser';

const useStyles = makeStyles({
  selectContainer: {
    width: 90,
    marginTop: -1,

    '& .MuiOutlinedInput-root': {
      fontSize: '1.2rem',
    },
    '& .MuiSelect-iconOutlined': {
      top: 'calc(50% - 8px)',
    },
    '& .MuiOutlinedInput-root.Mui-focused .MuiOutlinedInput-notchedOutline': {
      borderColor: '#2684FF',
    },
  },
  input: {
    '& .MuiOutlinedInput-inputMarginDense': {
      paddingTop: 11,
      paddingBottom: 11,
    },
  },
  success: {
    '& .MuiOutlinedInput-root .MuiOutlinedInput-notchedOutline': {
      borderColor: 'green',
    },
  },
  error: {
    '& .MuiOutlinedInput-root .MuiOutlinedInput-notchedOutline': {
      borderColor: 'red',
    },
  },
  select: {
    '& li': {
      fontSize: '1.2rem',
    },
    '& li:hover': {
      backgroundColor: '#DAEAFF',
    },
    '& .Mui-selected': {
      backgroundColor: '#2684FF',
    },
    '& .Mui-selected:hover': {
      backgroundColor: '#2684FF',
    },
  },
});

interface IOption {
  value: number;
  label: string;
}

interface IProps {
  currentVersion: number;
  selectedVersion: number;
  selectVersion(version: number): void;
}

const VersionSelectComponent = ({
  selectedVersion,
  currentVersion,
  selectVersion,
}: IProps) => {
  const [selectedOption, setSelectedOption] = React.useState<IOption>(null);
  const [options, setOptions] = React.useState<IOption[]>([]);

  const classes = useStyles();

  const setDefaultOptions = () => {
    const options = _.times(currentVersion, (i) => ({
      value: ++i,
      label: Parser.getVersionString(i),
    })).reverse();

    setOptions(options);
    setSelectedOption(options[options.length - selectedVersion]);
  };

  React.useEffect(() => {
    setDefaultOptions();
  }, [currentVersion, selectedVersion]);

  const handleSelect = (
    event: React.ChangeEvent<{
      name?: string;
      value: number;
    }>,
  ) => {
    if (event.target.value) {
      const selectedOption = options.find(
        (o) => o.value === event.target.value,
      );
      if (!selectedOption) {
        return;
      }
      setSelectedOption({
        value: selectedOption.value,
        label: selectedOption.label,
      });
      selectVersion(selectedOption.value);
    }
  };

  return (
    !!selectedOption && (
      <FormControl
        variant="outlined"
        size="small"
        className={`${classes.selectContainer} ${
          selectedVersion === currentVersion ? classes.success : classes.error
        }`}>
        <Select
          value={selectedOption?.value}
          onChange={handleSelect}
          input={<OutlinedInput className={classes.input} />}
          MenuProps={{ classes: { paper: classes.select } }}>
          {options.map((o, i) => {
            return (
              <MenuItem key={o.label + i} value={o.value}>
                {o.label}
              </MenuItem>
            );
          })}
        </Select>
      </FormControl>
    )
  );
};

const ComposedVersionSelectComponent: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('VersionSelectComponent'),
)(VersionSelectComponent);

export default ComposedVersionSelectComponent;
